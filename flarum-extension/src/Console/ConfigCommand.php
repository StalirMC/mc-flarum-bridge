<?php

namespace Stalir\McBridge\Console;

use Flarum\Console\AbstractCommand;
use Flarum\Settings\SettingsRepositoryInterface;
use Stalir\McBridge\Service\BridgeMessages;
use Symfony\Component\Console\Input\InputOption;

/**
 * php flarum mc-bridge:config --show
 * php flarum mc-bridge:config --tags=1,3
 * php flarum mc-bridge:config --locale=en
 *
 * Output language follows the mc-bridge.locale setting (Simplified Chinese by
 * default). Changing it with --locale takes effect from the next command run,
 * so the summary printed at the end of this run still uses the old language.
 */
class ConfigCommand extends AbstractCommand
{
    public function __construct(
        protected SettingsRepositoryInterface $settings,
        protected BridgeMessages $messages
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        parent::configure();

        $this
            ->setName('mc-bridge:config')
            ->setDescription('View or change MC Bridge settings (language, announcement tags, reply syncing)')
            ->addOption('show', null, InputOption::VALUE_NONE, 'Only print the current settings')
            ->addOption(
                'locale',
                null,
                InputOption::VALUE_REQUIRED,
                'Output language, e.g. zh-Hans or en'
            )
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
            $changed += $this->applyLocale();
            $changed += $this->applyTags();
            $changed += $this->applySyncReplies();
            $changed += $this->applyMaxAge();
        }

        $this->render();

        if ($this->option('show')) {
            return self::SUCCESS;
        }

        if ($changed === 0) {
            $this->comment($this->messages->get('console.config.nothing_changed'));
            $this->line('');
        } else {
            $this->info($this->messages->get('console.config.updated', ['%count%' => (string) $changed]));
            $this->comment($this->messages->get('console.config.note'));
            $this->line('');
        }

        return self::SUCCESS;
    }

    private function applyLocale(): int
    {
        $raw = $this->option('locale');

        if ($raw === null) {
            return 0;
        }

        $locale = trim((string) $raw);

        if (! BridgeMessages::isAvailable($locale)) {
            $this->error($this->messages->get('console.config.unknown_locale', [
                '%locale%' => $locale,
                '%available%' => implode(', ', BridgeMessages::availableLocales()),
            ]));

            return 0;
        }

        $this->settings->set(BridgeMessages::SETTING_KEY, $locale);
        $this->info($this->messages->get('console.config.locale_set', ['%locale%' => $locale]));

        return 1;
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
            $this->info($this->messages->get('console.config.tags_cleared'));

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
            $this->error($this->messages->get('console.config.invalid_tags', ['%tags%' => implode(', ', $invalid)]));
            $this->comment($this->messages->get('console.config.tag_hint'));

            return 0;
        }

        $unique = array_values(array_unique($ids));
        sort($unique);

        $this->settings->set('mc-bridge.announcement_tag_ids', implode(',', $unique));
        $this->info($this->messages->get('console.config.tags_set', ['%tags%' => implode(', ', $unique)]));

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
            $this->error($this->messages->get('console.config.must_be_boolean'));

            return 0;
        }

        $this->settings->set('mc-bridge.sync_replies', $raw);
        $this->info($this->messages->get($raw === '1' ? 'console.config.sync_replies_on' : 'console.config.sync_replies_off'));

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
            $this->error($this->messages->get('console.config.must_be_non_negative'));

            return 0;
        }

        $this->settings->set('mc-bridge.max_announcement_age_days', $raw);
        $this->info($this->messages->get('console.config.retention', ['%days%' => $raw]));

        return 1;
    }

    private function render(): void
    {
        $secret = (string) $this->settings->get('mc-bridge.secret', '');
        $tags = (string) $this->settings->get('mc-bridge.announcement_tag_ids', '');
        $syncReplies = (string) $this->settings->get('mc-bridge.sync_replies', '0');
        $maxAge = (string) $this->settings->get('mc-bridge.max_announcement_age_days', '30');
        $locale = BridgeMessages::resolveLocale($this->settings);

        $secretValue = $secret === ''
            ? $this->messages->get('console.config.value_secret_unset')
            : $this->messages->get('console.config.value_secret_set', ['%length%' => (string) strlen($secret)]);

        $this->line('');
        $this->info($this->messages->get('console.config.title'));
        $this->line('');

        $rows = [
            [$this->messages->get('console.config.label_locale'), $locale],
            [$this->messages->get('console.config.label_secret'), $secretValue],
            [
                $this->messages->get('console.config.label_tags'),
                $tags === '' ? $this->messages->get('console.config.value_tags_all') : $tags,
            ],
            [
                $this->messages->get('console.config.label_sync_replies'),
                $this->messages->get($syncReplies === '1' ? 'console.config.value_yes' : 'console.config.value_no'),
            ],
            [
                $this->messages->get('console.config.label_retention'),
                $this->messages->get('console.config.value_retention', ['%days%' => $maxAge]),
            ],
        ];

        foreach ($rows as [$label, $value]) {
            $this->line(sprintf('  %-24s %s', $label, $value));
        }

        $this->line('');
    }
}
