<?php

namespace Stalir\McBridge\Console;

use Flarum\Settings\SettingsRepositoryInterface;
use Illuminate\Database\ConnectionInterface;
use Stalir\McBridge\Model\McBindCode;
use Stalir\McBridge\Model\McOutboxMessage;
use Stalir\McBridge\Service\BridgeCrypto;
use Stalir\McBridge\Service\BridgeMessages;
use Symfony\Component\Console\Input\InputOption;

/**
 * php flarum mc-bridge:selftest
 *
 * Verifies, on the machine that actually runs the forum, that the bridge is
 * configured and functional. Add --url=https://your.forum to additionally
 * perform a real signed loopback request through the web server, which is the
 * only way to prove the whole HTTP stack (routing, CSRF gate, signature,
 * persistence) works end to end.
 *
 * Output language follows the mc-bridge.locale setting (Simplified Chinese by
 * default) and can be changed with: php flarum mc-bridge:config --locale=en
 */
class SelfTestCommand extends AbstractBridgeCommand
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
        SettingsRepositoryInterface $settings,
        BridgeMessages $messages,
        protected ConnectionInterface $db
    ) {
        parent::__construct($settings, $messages);
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

    /** Translate a key below `console.selftest.`. */
    private function t(string $key, array $replace = []): string
    {
        return $this->messages->get('console.selftest.'.$key, $replace);
    }

    // ------------------------------------------------------------------
    // Checks
    // ------------------------------------------------------------------

    private function checkSecret(string $secret): void
    {
        if ($secret === '') {
            $this->fail($this->t('check.secret_title'), $this->t('check.secret_missing'));

            return;
        }

        if (strlen($secret) < 32) {
            $this->fail($this->t('check.secret_title'), $this->t('check.secret_short'));

            return;
        }

        $this->pass(
            $this->t('check.secret_title'),
            $this->t('check.secret_ok', ['%length%' => (string) strlen($secret)])
        );
    }

    private function checkCrypto(string $secret): void
    {
        if ($secret === '') {
            $this->fail($this->t('check.crypto_title'), $this->t('check.crypto_skipped'));

            return;
        }

        $timestamp = (string) time();
        $nonce = bin2hex(random_bytes(16));
        $path = '/api/mc-bridge/heartbeat';
        $body = '{"server_key":"selftest","online":true}';

        $signature = BridgeCrypto::sign($secret, $timestamp, $nonce, 'POST', $path, $body);

        if (! BridgeCrypto::verify($secret, $timestamp, $nonce, 'POST', $path, $body, $signature)) {
            $this->fail($this->t('check.crypto_title'), $this->t('check.crypto_verify_failed'));

            return;
        }

        if (BridgeCrypto::verify($secret, $timestamp, $nonce, 'POST', $path, $body . 'x', $signature)) {
            $this->fail($this->t('check.crypto_title'), $this->t('check.crypto_tamper_failed'));

            return;
        }

        if (BridgeCrypto::verify('wrong-secret-wrong-secret-wrong', $timestamp, $nonce, 'POST', $path, $body, $signature)) {
            $this->fail($this->t('check.crypto_title'), $this->t('check.crypto_separation_failed'));

            return;
        }

        $this->pass($this->t('check.crypto_title'), $this->t('check.crypto_ok'));
    }

    private function checkCanonicalShape(): void
    {
        $canonical = BridgeCrypto::canonicalString('1700000000', 'nonce1234', 'post', '/api/mc-bridge/events', '{"a":1}');
        $expected = "1700000000\nnonce1234\nPOST\n/api/mc-bridge/events\n{\"a\":1}";

        if ($canonical !== $expected || substr_count($canonical, "\n") !== 4) {
            $this->fail($this->t('check.canonical_title'), $this->t('check.canonical_bad'));

            return;
        }

        $this->pass($this->t('check.canonical_title'), $this->t('check.canonical_ok'));
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
                $this->fail($this->t('check.path_title'), $this->t('check.path_bad', [
                    '%input%' => $input,
                    '%actual%' => $actual,
                    '%expected%' => $expected,
                ]));

                return;
            }
        }

        $this->pass($this->t('check.path_title'), $this->t('check.path_ok'));
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
            $this->fail($this->t('check.tables_title'), $this->t('check.tables_missing', [
                '%tables%' => implode(', ', $missing),
            ]));

            return;
        }

        $this->pass($this->t('check.tables_title'), $this->t('check.tables_ok', [
            '%count%' => (string) count(self::REQUIRED_TABLES),
        ]));
    }

    private function checkQueues(): void
    {
        try {
            $pending = McOutboxMessage::query()->whereNull('delivered_at')->count();
            $servers = $this->db->table('mc_servers')->count();
        } catch (\Throwable $exception) {
            $this->fail($this->t('check.queries_title'), $this->t('check.queries_failed', [
                '%message%' => $exception->getMessage(),
            ]));

            return;
        }

        $this->pass($this->t('check.queries_title'), $this->t('check.queries_ok', [
            '%pending%' => (string) $pending,
            '%servers%' => (string) $servers,
        ]));
    }

    private function checkBindCodeAlphabet(): void
    {
        for ($i = 0; $i < 200; $i++) {
            $code = McBindCode::generateCode();

            if (strlen($code) !== 8 || ! preg_match('/^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$/', $code)) {
                $this->fail($this->t('check.bind_title'), $this->t('check.bind_bad', ['%code%' => $code]));

                return;
            }
        }

        $this->pass($this->t('check.bind_title'), $this->t('check.bind_ok'));
    }

    /**
     * Perform a real signed HTTP request against the forum's own endpoint. This
     * also proves the CSRF bypass middleware is wired up correctly.
     */
    private function checkLiveLoopback(string $url, string $secret): void
    {
        if ($secret === '') {
            $this->fail($this->t('check.live_title'), $this->t('check.live_skipped_no_secret'));

            return;
        }

        if (! class_exists(\GuzzleHttp\Client::class)) {
            $this->fail($this->t('check.live_title'), $this->t('check.live_no_guzzle'));

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
                $this->fail($this->t('check.live_title'), $this->t('check.live_http_error', [
                    '%path%' => $path,
                    '%status%' => (string) $status,
                    '%body%' => mb_substr($payload, 0, 200),
                ]));

                return;
            }

            $decoded = json_decode($payload, true);

            if (! is_array($decoded) || ($decoded['ok'] ?? false) !== true) {
                $this->fail($this->t('check.live_title'), $this->t('check.live_not_ok'));

                return;
            }

            $this->pass($this->t('check.live_title'), $this->t('check.live_ok'));

            // Clean up the probe row so it does not show up as a real server.
            try {
                $this->db->table('mc_servers')->where('server_key', $serverKey)->delete();
                $this->pass($this->t('check.probe_cleanup'), $this->t('check.probe_cleanup_ok'));
            } catch (\Throwable $exception) {
                $this->note($this->t('check.probe_cleanup'), $this->t('check.probe_cleanup_failed', [
                    '%message%' => $exception->getMessage(),
                ]));
            }
        } catch (\Throwable $exception) {
            $this->fail($this->t('check.live_title'), $exception->getMessage());
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
        $this->info($this->t('title'));
        $this->line('');

        foreach ($this->results as $result) {
            $mark = match ($result['ok']) {
                true => $this->t('mark_ok'),
                false => $this->t('mark_fail'),
                default => $this->t('mark_note'),
            };

            $this->line(sprintf('%s %-22s %s', $mark, $result['label'], $result['detail']));
        }

        $this->line('');

        if (! $liveRequested) {
            $this->comment($this->t('live_skipped'));
            $this->line($this->t('live_hint'));
            $this->line('');
        }

        if ($this->failures === 0) {
            $this->info($this->t('all_passed'));
            $this->line($this->t('next_step'));
        } else {
            $this->error($this->t('failed', ['%count%' => (string) $this->failures]));
            $this->line($this->t('troubleshoot'));
        }

        $this->line('');
    }
}
