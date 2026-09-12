<?php

namespace Stalir\McBridge\Console;

use Flarum\Console\AbstractCommand;
use Flarum\Settings\SettingsRepositoryInterface;
use Stalir\McBridge\Service\BridgeMessages;

/**
 * Shared base for this extension's console commands.
 *
 * Flarum's AbstractCommand extends Symfony's Command directly, NOT
 * Illuminate\Console\Command. It therefore provides only:
 *
 *   info(), comment(), error(), hasOption()   plus   $this->input / $this->output
 *
 * The Illuminate helpers people usually reach for - option(), argument(),
 * line(), table(), ask(), ... - do NOT exist here and calling them is a fatal
 * "Call to undefined method" at runtime. The shims below keep the command code
 * readable; anything beyond them should use $this->input / $this->output
 * directly.
 */
abstract class AbstractBridgeCommand extends AbstractCommand
{
    public function __construct(
        protected SettingsRepositoryInterface $settings,
        protected BridgeMessages $messages
    ) {
        parent::__construct();
    }

    /** Symfony's InputInterface::getOption(). */
    protected function option(string $name): mixed
    {
        return $this->input->getOption($name);
    }

    /** Symfony's InputInterface::getArgument(). */
    protected function argument(string $name): mixed
    {
        return $this->input->getArgument($name);
    }

    /** Raw line output; info()/comment()/error() wrap the text in tags. */
    protected function line(string $message = ''): void
    {
        $this->output->writeln($message);
    }
}
