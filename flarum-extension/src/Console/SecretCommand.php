<?php

namespace Stalir\McBridge\Console;

use Flarum\Console\AbstractCommand;
use Flarum\Settings\SettingsRepositoryInterface;
use Symfony\Component\Console\Input\InputArgument;
use Symfony\Component\Console\Input\InputOption;

/**
 * php flarum mc-bridge:secret            # generate and store a new secret
 * php flarum mc-bridge:secret --show     # print the current secret
 * php flarum mc-bridge:secret <value>    # store a specific secret
 */
class SecretCommand extends AbstractCommand
{
    public function __construct(
        protected SettingsRepositoryInterface $settings
    ) {
        parent::__construct();
    }

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
                $this->comment('No secret is configured yet.');

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
            $this->error('The secret must be at least 32 characters long.');

            return self::FAILURE;
        }

        $this->settings->set('mc-bridge.secret', $secret);

        $this->info('MC Bridge secret stored.');
        $this->line('');
        $this->line('Put this value in the plugin config.yml as security.secret:');
        $this->line('');
        $this->line('  ' . $secret);
        $this->line('');

        if ($current !== '' && ! hash_equals($current, $secret)) {
            $this->comment('Note: the previous secret was replaced. Update your servers.');
        }

        return self::SUCCESS;
    }
}
