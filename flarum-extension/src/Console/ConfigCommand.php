<?php

namespace Stalir\McBridge\Console;

use Stalir\McBridge\Service\BridgeMessages;
use Stalir\McBridge\Service\ReportDiscussion;
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
class ConfigCommand extends AbstractBridgeCommand
{
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
            )
            ->addOption(
                'report-tags',
                null,
                InputOption::VALUE_REQUIRED,
                'Comma separated tag ids that player reports are filed under '
                . '(empty = detect the tag by its "reports" slug)'
            )
            ->addOption(
                'report-actor',
                null,
                InputOption::VALUE_REQUIRED,
                'User id that report discussions are authored as (empty = the oldest administrator)'
            )
            ->addOption(
                'report-title',
                null,
                InputOption::VALUE_REQUIRED,
                'Title template used when the game server sends none; tokens: {target} {reporter} {reason} {server}'
            )
            ->addOption(
                'report-resolved-tags',
                null,
                InputOption::VALUE_REQUIRED,
                'Comma separated tag ids that mean "this report was dealt with"; '
                . 'moving a report discussion into one tells the reporter in game (empty = off)'
            )
            ->addOption(
                'report-rejected-tags',
                null,
                InputOption::VALUE_REQUIRED,
                'Comma separated tag ids that mean "this report was dismissed" (empty = off)'
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
            $changed += $this->applyIdList(
                'report-tags',
                ReportDiscussion::TAGS_SETTING,
                'console.config.report_tags_set',
                'console.config.report_tags_cleared'
            );
            $changed += $this->applyId(
                'report-actor',
                ReportDiscussion::ACTOR_SETTING,
                'console.config.report_actor_set',
                'console.config.report_actor_cleared'
            );
            $changed += $this->applyReportTitle();
            $changed += $this->applyIdList(
                'report-resolved-tags',
                ReportDiscussion::RESOLVED_TAGS_SETTING,
                'console.config.report_resolved_tags_set',
                'console.config.report_resolved_tags_cleared'
            );
            $changed += $this->applyIdList(
                'report-rejected-tags',
                ReportDiscussion::REJECTED_TAGS_SETTING,
                'console.config.report_rejected_tags_set',
                'console.config.report_rejected_tags_cleared'
            );
        }

        $this->render();

        if ($this->option('show')) {
            return self::SUCCESS;
        }

        if ($changed === 0) {
            $this->comment($this->messages->get('console.config.nothing_changed'));
            $this->line('');
        } else {
            $this->info($this->messages->get('console.config.updated', ['count' => (string) $changed]));
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
                'locale' => $locale,
                'available' => implode(', ', BridgeMessages::availableLocales()),
            ]));

            return 0;
        }

        $this->settings->set(BridgeMessages::SETTING_KEY, $locale);
        $this->info($this->messages->get('console.config.locale_set', ['locale' => $locale]));

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
            $this->error($this->messages->get('console.config.invalid_tags', ['tags' => implode(', ', $invalid)]));
            $this->comment($this->messages->get('console.config.tag_hint'));

            return 0;
        }

        $unique = array_values(array_unique($ids));
        sort($unique);

        $this->settings->set('mc-bridge.announcement_tag_ids', implode(',', $unique));
        $this->info($this->messages->get('console.config.tags_set', ['tags' => implode(', ', $unique)]));

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
        $this->info($this->messages->get('console.config.retention', ['days' => $raw]));

        return 1;
    }

    /**
     * Set or clear a setting that holds a positive id.
     *
     * An empty value is meaningful for both report settings: it means "work it
     * out automatically", which is what Service\ReportDiscussion does when the
     * key is blank.
     */
    private function applyId(string $option, string $setting, string $setKey, string $clearKey): int
    {
        $raw = $this->option($option);

        if ($raw === null) {
            return 0;
        }

        $raw = trim((string) $raw);

        if ($raw === '') {
            $this->settings->set($setting, '');
            $this->info($this->messages->get($clearKey));

            return 1;
        }

        if (! ctype_digit($raw) || (int) $raw <= 0) {
            $this->error($this->messages->get('console.config.must_be_positive_id', [
                'option' => $option,
                'value' => $raw,
            ]));

            return 0;
        }

        $this->settings->set($setting, $raw);
        $this->info($this->messages->get($setKey, ['id' => $raw]));

        return 1;
    }

    /**
     * Set or clear a setting that holds a comma separated list of positive ids.
     *
     * An empty value means "work it out automatically", which is what
     * Service\ReportDiscussion does when the key is blank.
     */
    private function applyIdList(string $option, string $setting, string $setKey, string $clearKey): int
    {
        $raw = $this->option($option);

        if ($raw === null) {
            return 0;
        }

        $raw = trim((string) $raw);

        if ($raw === '') {
            $this->settings->set($setting, '');
            $this->info($this->messages->get($clearKey));

            return 1;
        }

        $ids = [];

        foreach (explode(',', $raw) as $part) {
            $part = trim($part);

            if ($part === '') {
                continue;
            }

            if (! ctype_digit($part) || (int) $part <= 0) {
                $this->error($this->messages->get('console.config.must_be_positive_id', [
                    'option' => $option,
                    'value' => $part,
                ]));

                return 0;
            }

            $ids[] = (int) $part;
        }

        if ($ids === []) {
            $this->error($this->messages->get('console.config.must_be_positive_id', [
                'option' => $option,
                'value' => $raw,
            ]));

            return 0;
        }

        $unique = array_values(array_unique($ids));

        $this->settings->set($setting, implode(',', $unique));
        $this->info($this->messages->get($setKey, ['ids' => implode(', #', $unique)]));

        return 1;
    }

    /**
     * The report title template is free text rather than an id, so an empty
     * value means "go back to the built-in default" instead of being invalid.
     */
    private function applyReportTitle(): int
    {
        $raw = $this->option('report-title');

        if ($raw === null) {
            return 0;
        }

        $raw = trim((string) $raw);

        if ($raw === '') {
            $this->settings->set(ReportDiscussion::TITLE_SETTING, '');
            $this->info($this->messages->get('console.config.report_title_cleared'));

            return 1;
        }

        $this->settings->set(ReportDiscussion::TITLE_SETTING, $raw);
        $this->info($this->messages->get('console.config.report_title_set', ['format' => $raw]));

        return 1;
    }

    private function render(): void
    {
        $secret = (string) $this->settings->get('mc-bridge.secret', '');
        $tags = (string) $this->settings->get('mc-bridge.announcement_tag_ids', '');
        $syncReplies = (string) $this->settings->get('mc-bridge.sync_replies', '0');
        $maxAge = (string) $this->settings->get('mc-bridge.max_announcement_age_days', '30');
        $reportTags = (string) $this->settings->get(ReportDiscussion::TAGS_SETTING, '');
        $reportActor = (string) $this->settings->get(ReportDiscussion::ACTOR_SETTING, '');
        $reportTitle = (string) $this->settings->get(ReportDiscussion::TITLE_SETTING, '');
        $reportResolved = (string) $this->settings->get(ReportDiscussion::RESOLVED_TAGS_SETTING, '');
        $reportRejected = (string) $this->settings->get(ReportDiscussion::REJECTED_TAGS_SETTING, '');
        $locale = BridgeMessages::resolveLocale($this->settings);

        $secretValue = $secret === ''
            ? $this->messages->get('console.config.value_secret_unset')
            : $this->messages->get('console.config.value_secret_set', ['length' => (string) strlen($secret)]);

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
                $this->messages->get('console.config.value_retention', ['days' => $maxAge]),
            ],
            [
                $this->messages->get('console.config.label_report_tags'),
                $reportTags === ''
                    ? $this->messages->get('console.config.value_report_auto')
                    : '#' . str_replace(',', ', #', $reportTags),
            ],
            [
                $this->messages->get('console.config.label_report_actor'),
                $reportActor === ''
                    ? $this->messages->get('console.config.value_report_auto')
                    : '#' . $reportActor,
            ],
            [
                $this->messages->get('console.config.label_report_title'),
                $reportTitle === ''
                    ? $this->messages->get('console.config.value_report_title_default')
                    : $reportTitle,
            ],
            [
                $this->messages->get('console.config.label_report_resolved_tags'),
                $reportResolved === ''
                    ? $this->messages->get('console.config.value_report_outcome_off')
                    : '#' . str_replace(',', ', #', $reportResolved),
            ],
            [
                $this->messages->get('console.config.label_report_rejected_tags'),
                $reportRejected === ''
                    ? $this->messages->get('console.config.value_report_outcome_off')
                    : '#' . str_replace(',', ', #', $reportRejected),
            ],
        ];

        foreach ($rows as [$label, $value]) {
            $this->line(sprintf('  %-24s %s', $label, $value));
        }

        $this->line('');
    }
}
