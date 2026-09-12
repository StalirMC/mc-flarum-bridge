<?php

namespace Stalir\McBridge\Console;

use Symfony\Component\Console\Input\InputArgument;
use Symfony\Component\Console\Input\InputOption;

/**
 * php flarum mc-bridge:secret            # generate and store a new secret
 * php flarum mc-bridge:secret --show     # print the current secret
 * php flarum mc-bridge:secret <value>    # store a specific secret
 *
 * Output language follows the mc-bridge.locale setting (Simplified Chinese by
 * default) and can be changed with: php flarum mc-bridge:config --locale=en
 */
class SecretCommand extends AbstractBridgeCommand
{
    protected function configure(): void
    {
        parent::configure();

        $this
            ->setName('mc-bridge:secret')
            ->setDescription('Show or rotate the shared secret used to sign MC Bridge requests')
            ->addArgument('secret', InputArgument::OPTIONAL, 'Secret to store (omit to generate a random one)')
            ->addOption('show', null, InputOption::VALUE_NONE, 'Print the currently stored secret');
    }

    protected function fire(): int
    {
        $current = (string) $this->settings->get('mc-bridge.secret', '');

        if ($this->option('show')) {
            if ($current === '') {
                $this->comment($this->messages->get('console.secret.missing'));

                return self::SUCCESS;
            }

            $this->line($current);

            return self::SUCCESS;
        }

        $secret = $this->argument('secret');

        if (! is_string($secret) || trim($secret) === '') {
            $secret = bin2hex(random_bytes(32));
        } else {
            $secret = trim($secret);
        }

        if (strlen($secret) < 32) {
            $this->error($this->messages->get('console.secret.too_short'));

            return self::FAILURE;
        }

        $this->settings->set('mc-bridge.secret', $secret);

        $this->info($this->messages->get('console.secret.stored'));
        $this->line('');
        $this->line($this->messages->get('console.secret.paste_intro'));
        $this->line('');
        $this->line($this->messages->get('console.secret.paste_line', ['secret' => $secret]));
        $this->line('');

        if ($current !== '' && ! hash_equals($current, $secret)) {
            $this->comment($this->messages->get('console.secret.replaced'));
        }

        return self::SUCCESS;
    }
}
