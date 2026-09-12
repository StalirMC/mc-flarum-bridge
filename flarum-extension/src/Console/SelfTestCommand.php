<?php

namespace Stalir\McBridge\Console;

use Flarum\Console\AbstractCommand;
use Flarum\Settings\SettingsRepositoryInterface;
use Illuminate\Database\ConnectionInterface;
use Stalir\McBridge\Model\McBindCode;
use Stalir\McBridge\Model\McOutboxMessage;
use Stalir\McBridge\Service\BridgeCrypto;
use Symfony\Component\Console\Input\InputOption;

/**
 * php flarum mc-bridge:selftest
 *
 * Verifies, on the machine that actually runs the forum, that the bridge is
 * configured and functional. Add --url=https://your.forum to additionally
 * perform a real signed loopback request through the web server, which is the
 * only way to prove the whole HTTP stack (routing, CSRF gate, signature,
 * persistence) works end to end.
 */
class SelfTestCommand extends AbstractCommand
{
    private const REQUIRED_TABLES = [
        'mc_servers',
        'mc_events',
        'mc_outbox',
        'mc_bindings',
        'mc_bind_codes',
    ];

    private array $results = [];
    private int $failures = 0;

    public function __construct(
        protected SettingsRepositoryInterface $settings,
        protected ConnectionInterface $db
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        parent::configure();

        $this
            ->setName('mc-bridge:selftest')
            ->setDescription('Check that the MC Bridge is configured correctly and can sign/verify requests')
            ->addOption(
                'url',
                null,
                InputOption::VALUE_REQUIRED,
                'Base URL of this forum (e.g. https://forum.example.com) to also run a live signed loopback request'
            );
    }

    protected function fire(): int
    {
        $this->results = [];
        $this->failures = 0;

        $secret = (string) $this->settings->get('mc-bridge.secret', '');

        $this->checkSecret($secret);
        $this->checkCrypto($secret);
        $this->checkCanonicalShape();
        $this->checkPathNormalisation();
        $this->checkTables();
        $this->checkQueues();
        $this->checkBindCodeAlphabet();

        $url = $this->option('url');
        $liveRequested = is_string($url) && $url !== '';

        if ($liveRequested) {
            $this->checkLiveLoopback($url, $secret);
        }

        $this->render($liveRequested);

        return $this->failures === 0 ? self::SUCCESS : self::FAILURE;
    }

    // ------------------------------------------------------------------
    // Checks
    // ------------------------------------------------------------------

    private function checkSecret(string $secret): void
    {
        if ($secret === '') {
            $this->fail('Shared secret', 'not configured - run: php flarum mc-bridge:secret');

            return;
        }

        if (strlen($secret) < 32) {
            $this->fail('Shared secret', 'shorter than 32 characters; rotate it with mc-bridge:secret');

            return;
        }

        $this->pass('Shared secret', 'configured (' . strlen($secret) . ' characters)');
    }

    private function checkCrypto(string $secret): void
    {
        if ($secret === '') {
            $this->fail('HMAC round trip', 'skipped because no secret is configured');

            return;
        }

        $timestamp = (string) time();
        $nonce = bin2hex(random_bytes(16));
        $path = '/api/mc-bridge/heartbeat';
        $body = '{"server_key":"selftest","online":true}';

        $signature = BridgeCrypto::sign($secret, $timestamp, $nonce, 'POST', $path, $body);

        if (! BridgeCrypto::verify($secret, $timestamp, $nonce, 'POST', $path, $body, $signature)) {
            $this->fail('HMAC round trip', 'a signature produced by BridgeCrypto did not verify');

            return;
        }

        if (BridgeCrypto::verify($secret, $timestamp, $nonce, 'POST', $path, $body . 'x', $signature)) {
            $this->fail('HMAC tamper detection', 'a modified body still verified - do not use this build');

            return;
        }

        if (BridgeCrypto::verify('wrong-secret-wrong-secret-wrong', $timestamp, $nonce, 'POST', $path, $body, $signature)) {
            $this->fail('HMAC key separation', 'a different secret also verified the signature');

            return;
        }

        $this->pass('HMAC round trip', 'sign/verify ok, tampering rejected');
    }

    private function checkCanonicalShape(): void
    {
        $canonical = BridgeCrypto::canonicalString('1700000000', 'nonce1234', 'post', '/api/mc-bridge/events', '{"a":1}');
        $expected = "1700000000\nnonce1234\nPOST\n/api/mc-bridge/events\n{\"a\":1}";

        if ($canonical !== $expected || substr_count($canonical, "\n") !== 4) {
            $this->fail('Canonical string', 'unexpected format; the plugin will not be able to authenticate');

            return;
        }

        $this->pass('Canonical string', 'timestamp\\nnonce\\nMETHOD\\npath\\nbody');
    }

    private function checkPathNormalisation(): void
    {
        $cases = [
            '/api/mc-bridge/outbox?server_key=survival&peek=1' => '/api/mc-bridge/outbox',
            '/forum/api/mc-bridge/heartbeat' => '/api/mc-bridge/heartbeat',
            'https://example.com/sub/api/mc-bridge/events' => '/api/mc-bridge/events',
        ];

        foreach ($cases as $input => $expected) {
            $actual = BridgeCrypto::normalizePath($input);

            if ($actual !== $expected) {
                $this->fail(
                    'Path normalisation',
                    sprintf('normalizePath(%s) returned %s, expected %s', $input, $actual, $expected)
                );

                return;
            }
        }

        $this->pass('Path normalisation', 'sub-directory installs and query strings handled');
    }

    private function checkTables(): void
    {
        $schema = $this->db->getSchemaBuilder();
        $missing = [];

        foreach (self::REQUIRED_TABLES as $table) {
            if (! $schema->hasTable($table)) {
                $missing[] = $table;
            }
        }

        if ($missing !== []) {
            $this->fail('Database tables', 'missing: ' . implode(', ', $missing) . ' - run: php flarum migrate');

            return;
        }

        $this->pass('Database tables', count(self::REQUIRED_TABLES) . ' tables present');
    }

    private function checkQueues(): void
    {
        try {
            $pending = McOutboxMessage::query()->whereNull('delivered_at')->count();
            $servers = $this->db->table('mc_servers')->count();
        } catch (\Throwable $exception) {
            $this->fail('Query paths', $exception->getMessage());

            return;
        }

        $this->pass('Query paths', sprintf('%d pending outbox message(s), %d known server(s)', $pending, $servers));
    }

    private function checkBindCodeAlphabet(): void
    {
        for ($i = 0; $i < 200; $i++) {
            $code = McBindCode::generateCode();

            if (strlen($code) !== 8 || ! preg_match('/^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$/', $code)) {
                $this->fail('Binding code format', 'generated code "' . $code . '" is not 8 unambiguous characters');

                return;
            }
        }

        $this->pass('Binding code format', '200 samples, 8 chars, no 0/O/1/I');
    }

    /**
     * Perform a real signed HTTP request against the forum's own endpoint. This
     * also proves the CSRF bypass middleware is wired up correctly.
     */
    private function checkLiveLoopback(string $url, string $secret): void
    {
        if ($secret === '') {
            $this->fail('Live loopback', 'skipped because no secret is configured');

            return;
        }

        if (! class_exists(\GuzzleHttp\Client::class)) {
            $this->fail('Live loopback', 'Guzzle is not available in this installation');

            return;
        }

        $url = rtrim($url, '/');
        $path = '/api/mc-bridge/heartbeat';
        $serverKey = 'selftest';
        $body = (string) json_encode([
            'server_key' => $serverKey,
            'online' => true,
            'players_online' => 0,
            'players_max' => 0,
            'version' => 'selftest',
        ]);

        $timestamp = (string) time();
        $nonce = bin2hex(random_bytes(16));
        $signature = BridgeCrypto::sign($secret, $timestamp, $nonce, 'POST', $path, $body);

        try {
            $client = new \GuzzleHttp\Client([
                'timeout' => 10,
                'http_errors' => false,
                'verify' => false,
            ]);

            $response = $client->post($url . $path, [
                'body' => $body,
                'headers' => [
                    'Content-Type' => 'application/json',
                    'Accept' => 'application/json',
                    BridgeCrypto::HEADER_TIMESTAMP => $timestamp,
                    BridgeCrypto::HEADER_NONCE => $nonce,
                    BridgeCrypto::HEADER_SIGNATURE => $signature,
                    BridgeCrypto::HEADER_SERVER => $serverKey,
                ],
            ]);

            $status = $response->getStatusCode();
            $payload = (string) $response->getBody();

            if ($status < 200 || $status >= 300) {
                $this->fail(
                    'Live loopback',
                    sprintf('POST %s returned HTTP %d: %s', $path, $status, mb_substr($payload, 0, 200))
                );

                return;
            }

            $decoded = json_decode($payload, true);

            if (! is_array($decoded) || ($decoded['ok'] ?? false) !== true) {
                $this->fail('Live loopback', 'the endpoint answered but did not report ok=true');

                return;
            }

            $this->pass('Live loopback', 'signed heartbeat accepted by the web server');

            // Clean up the probe row so it does not show up as a real server.
            try {
                $this->db->table('mc_servers')->where('server_key', $serverKey)->delete();
                $this->pass('Probe cleanup', 'temporary selftest server row removed');
            } catch (\Throwable $exception) {
                $this->note('Probe cleanup', 'could not remove the selftest row: ' . $exception->getMessage());
            }
        } catch (\Throwable $exception) {
            $this->fail('Live loopback', $exception->getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    private function pass(string $label, string $detail): void
    {
        $this->results[] = ['ok' => true, 'label' => $label, 'detail' => $detail];
    }

    private function fail(string $label, string $detail): void
    {
        $this->failures++;
        $this->results[] = ['ok' => false, 'label' => $label, 'detail' => $detail];
    }

    private function note(string $label, string $detail): void
    {
        $this->results[] = ['ok' => null, 'label' => $label, 'detail' => $detail];
    }

    private function render(bool $liveRequested): void
    {
        $this->line('');
        $this->info('MC Bridge self test');
        $this->line('');

        foreach ($this->results as $result) {
            $mark = match ($result['ok']) {
                true => '  OK  ',
                false => ' FAIL ',
                default => ' NOTE ',
            };

            $this->line(sprintf('%s %-22s %s', $mark, $result['label'], $result['detail']));
        }

        $this->line('');

        if (! $liveRequested) {
            $this->comment('Live HTTP check skipped. Re-run with:');
            $this->line('  php flarum mc-bridge:selftest --url=https://your.forum');
            $this->line('');
        }

        if ($this->failures === 0) {
            $this->info('All bridge checks passed.');
            $this->line('Next: start the Minecraft server and watch for a successful heartbeat.');
        } else {
            $this->error(sprintf('%d check(s) failed.', $this->failures));
            $this->line('See docs/README.md section 6 (troubleshooting).');
        }

        $this->line('');
    }
}
