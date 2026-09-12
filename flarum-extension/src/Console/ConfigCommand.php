<?php

namespace Stalir\McBridge\Console;

use Flarum\Console\AbstractCommand;
use Flarum\Settings\SettingsRepositoryInterface;
use Symfony\Component\Console\Input\InputOption;

/**
 * php flarum mc-bridge:config --show
 * php flarum mc-bridge:config --tags=1,3
 * php flarum mc-bridge:config --tags= --sync-replies=1
 *
 * Without this command the announcement tag filter could only be changed
 * through `php flarum tinker`, which is easy to get wrong.
 */
class ConfigCommand extends AbstractCommand
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
            ->setName('mc-bridge:config')
            ->setDescription('View or change MC Bridge settings (announcement tags, reply syncing)')
            ->addOption('show', null, InputOption::VALUE_NONE, 'Only print the current settings')
            ->addOption(
                'tags',
                null,
                InputOption::VALUE_REQUIRED,
                'Comma separated tag IDs to push to the game. Pass an empty string to push every discussion.'
            )
            ->addOption(
                'sync-replies',
                null,
                InputOption::VALUE_REQUIRED,
                'Set to 1 to also push replies of matching discussions, 0 to push only new discussions'
            )
            ->addOption(
                'max-age-days',
                null,
                InputOption::VALUE_REQUIRED,
                'Retention window for queued announcements'
            );
    }

    protected function fire(): int
    {
        $changed = 0;

        if (! $this->option('show')) {
            $changed += $this->applyTags();
            $changed += $this->applySyncReplies();
            $changed += $this->applyMaxAge();
        }

        $this->render();

        if ($this->option('show')) {
            return self::SUCCESS;
        }

        if ($changed === 0) {
            $this->comment('Nothing changed. Pass --tags, --sync-replies or --max-age-days.');
            $this->line('');
        } else {
            $this->info(sprintf('%d setting(s) updated.', $changed));
            $this->comment('New discussions are matched from now on; already queued messages are unaffected.');
            $this->line('');
        }

        return self::SUCCESS;
    }

    private function applyTags(): int
    {
        $raw = $this->option('tags');

        if ($raw === null) {
            return 0;
        }

        $raw = trim((string) $raw);

        if ($raw === '') {
            $this->settings->set('mc-bridge.announcement_tag_ids', '');
            $this->info('Announcement tags cleared - every new discussion will be pushed.');

            return 1;
        }

        $ids = [];
        $invalid = [];

        foreach (explode(',', $raw) as $part) {
            $part = trim($part);

            if ($part === '') {
                continue;
            }

            if (! ctype_digit($part)) {
                $invalid[] = $part;
                continue;
            }

            $ids[] = (int) $part;
        }

        if ($invalid !== []) {
            $this->error('Not a tag ID: ' . implode(', ', $invalid));
            $this->comment("Tag IDs are integers. List them with: php flarum tinker --execute=\"echo \\Flarum\\Tags\\Tag::pluck('id','name');\"");

            return 0;
        }

        $unique = array_values(array_unique($ids));
        sort($unique);

        $this->settings->set('mc-bridge.announcement_tag_ids', implode(',', $unique));
        $this->info('Announcement tags set to ' . implode(', ', $unique));

        return 1;
    }

    private function applySyncReplies(): int
    {
        $raw = $this->option('sync-replies');

        if ($raw === null) {
            return 0;
        }

        $raw = trim((string) $raw);

        if (! in_array($raw, ['0', '1'], true)) {
            $this->error('--sync-replies must be 0 or 1.');

            return 0;
        }

        $this->settings->set('mc-bridge.sync_replies', $raw);
        $this->info('Reply syncing ' . ($raw === '1' ? 'enabled' : 'disabled'));

        return 1;
    }

    private function applyMaxAge(): int
    {
        $raw = $this->option('max-age-days');

        if ($raw === null) {
            return 0;
        }

        $raw = trim((string) $raw);

        if (! ctype_digit($raw)) {
            $this->error('--max-age-days must be a non-negative integer.');

            return 0;
        }

        $this->settings->set('mc-bridge.max_announcement_age_days', $raw);
        $this->info('Retention window set to ' . $raw . ' day(s)');

        return 1;
    }

    private function render(): void
    {
        $secret = (string) $this->settings->get('mc-bridge.secret', '');
        $tags = (string) $this->settings->get('mc-bridge.announcement_tag_ids', '');
        $syncReplies = (string) $this->settings->get('mc-bridge.sync_replies', '0');
        $maxAge = (string) $this->settings->get('mc-bridge.max_announcement_age_days', '30');

        $this->line('');
        $this->info('MC Bridge settings');
        $this->line('');
        $this->line(sprintf('  %-24s %s', 'secret', $secret === '' ? 'NOT SET' : 'configured (' . strlen($secret) . ' chars)'));
        $this->line(sprintf('  %-24s %s', 'announcement tags', $tags === '' ? 'all discussions' : $tags));
        $this->line(sprintf('  %-24s %s', 'sync replies', $syncReplies === '1' ? 'yes' : 'no'));
        $this->line(sprintf('  %-24s %s', 'max announcement age', $maxAge . ' day(s)'));
        $this->line('');
    }
}
