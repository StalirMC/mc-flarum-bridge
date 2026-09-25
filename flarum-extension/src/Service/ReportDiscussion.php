<?php

namespace Stalir\McBridge\Service;

use Flarum\Api\JsonApi;
use Flarum\Api\Resource\DiscussionResource;
use Flarum\Group\Group;
use Flarum\Settings\SettingsRepositoryInterface;
use Flarum\Tags\Tag;
use Flarum\User\User;
use Psr\Log\LoggerInterface;
use Stalir\McBridge\Model\McReport;
use Throwable;

/**
 * Turns a stored player report into a forum discussion.
 *
 * Moderators work from the forum, not from a database table, so a report that
 * only lands in `mc_reports` is effectively invisible. The discussion is created
 * through Flarum's own JSON:API pipeline - the same path the composer uses -
 * rather than by writing rows by hand, so the first post, the tag pivot, the
 * discussion counters and the author's read state are all correct without this
 * extension having to know how Flarum stores them.
 */
class ReportDiscussion
{
    /** Explicit tag id. When empty, the tag is found by slug/name instead. */
    public const TAG_SETTING = 'mc-bridge.report_tag_id';

    /** Explicit author. When empty, the oldest administrator is used. */
    public const ACTOR_SETTING = 'mc-bridge.report_actor_id';

    /** Slug and name used to recognise the report tag automatically. */
    public const TAG_SLUG = 'reports';
    public const TAG_NAME = '举报';

    public function __construct(
        protected JsonApi $api,
        protected SettingsRepositoryInterface $settings,
        protected LoggerInterface $log
    ) {
    }

    /**
     * Create the moderation discussion for a report that has already been saved.
     *
     * Deliberately best-effort: the report is persisted before this runs, and a
     * problem here must not turn a recorded report into a 500 for the player who
     * submitted it. The failure is logged instead.
     *
     * @return int|null the new discussion id, or null when it could not be made
     */
    public function create(McReport $report): ?int
    {
        try {
            return $this->dispatch($report);
        } catch (Throwable $exception) {
            $this->log->error(
                '[mc-bridge] could not turn a player report into a forum discussion',
                [
                    'report_id' => $report->id,
                    'target' => $report->target_name,
                    'exception' => $exception,
                ]
            );

            return null;
        }
    }

    private function dispatch(McReport $report): ?int
    {
        $actor = $this->actor();

        if ($actor === null) {
            $this->log->warning(
                '[mc-bridge] no administrator account found to author the report discussion; ' .
                'set one with: php flarum mc-bridge:config --report-actor=<user id>'
            );

            return null;
        }

        $tag = $this->tag();

        // Remember what was resolved. Without this the auto-detected tag would
        // exist only in memory, and QueueAnnouncement - which has to decide
        // whether a discussion is moderation material - would have nothing to
        // match on and would broadcast the report into in-game chat. Writing the
        // values also makes `mc-bridge:config --show` tell the truth, and an
        // admin can still point either one somewhere else at any time.
        $this->remember(self::ACTOR_SETTING, (int) $actor->id);

        if ($tag !== null) {
            $this->remember(self::TAG_SETTING, (int) $tag->id);
        }

        $data = [
            'type' => 'discussions',
            'attributes' => [
                'title' => $this->title($report),
                'content' => $this->content($report),
            ],
        ];

        if ($tag !== null) {
            // The tags resource is only present when flarum/tags is enabled, and
            // sending an unknown relationship would fail validation, so the key
            // is only added when a tag was actually resolved.
            $data['relationships'] = [
                'tags' => [
                    'data' => [
                        ['type' => 'tags', 'id' => (string) $tag->id],
                    ],
                ],
            ];

            if ($actor->cannot('startDiscussion', $tag)) {
                $this->log->error(
                    '[mc-bridge] the report author may not start discussions in the report tag, ' .
                    'so the discussion was not created',
                    ['tag_id' => $tag->id, 'user_id' => $actor->id]
                );

                return null;
            }
        }

        // Same call shape the framework itself uses to create a discussion with
        // its first post (see Flarum\Api\Resource\DiscussionResource::saveModel).
        $discussion = $this->api
            ->forResource(DiscussionResource::class)
            ->forEndpoint('create')
            ->process(['data' => $data], [], ['actor' => $actor]);

        return $discussion?->id;
    }

    /**
     * Who the report discussion is filed as.
     *
     * A privileged account is required: a member may not be allowed to start a
     * discussion in the report tag, and filing the report as the reporter would
     * publish their identity to everyone who can read that tag.
     */
    private function actor(): ?User
    {
        $configured = (int) $this->settings->get(self::ACTOR_SETTING, '');

        if ($configured > 0) {
            $user = User::find($configured);

            if ($user !== null) {
                return $user;
            }
        }

        return User::query()
            ->whereHas('groups', function ($query) {
                $query->where('id', Group::ADMINISTRATOR_ID);
            })
            ->orderBy('id')
            ->first();
    }

    /**
     * The tag every report is filed under.
     *
     * The explicit setting wins; otherwise a tag with the conventional slug is
     * adopted, which is how the tag on an existing forum gets used without any
     * configuration. When flarum/tags is enabled but no report tag exists yet,
     * one is created: discussions require at least one tag unless the actor may
     * bypass tag counts, so without it the report could not be filed at all.
     */
    private function tag(): ?Tag
    {
        if (! class_exists(Tag::class)) {
            return null;
        }

        $configured = (int) $this->settings->get(self::TAG_SETTING, '');

        if ($configured > 0) {
            $tag = Tag::find($configured);

            if ($tag !== null) {
                return $tag;
            }

            $this->log->warning(
                '[mc-bridge] the configured report tag no longer exists; falling back to detection',
                ['tag_id' => $configured]
            );
        }

        $existing = Tag::where('slug', self::TAG_SLUG)->first()
            ?? Tag::where('name', self::TAG_NAME)->first();

        if ($existing !== null) {
            return $existing;
        }

        $tag = Tag::build(self::TAG_NAME, self::TAG_SLUG, '', '#e9fe48', 'fas fa-exclamation-triangle', false);
        // Secondary tag on purpose: it must not push its way into the primary
        // navigation of a forum that never asked for it.
        $tag->is_primary = false;
        $tag->save();

        $this->log->info(
            '[mc-bridge] created the report tag; point the bridge elsewhere with: ' .
            'php flarum mc-bridge:config --report-tag=<tag id>',
            ['tag_id' => $tag->id]
        );

        return $tag;
    }

    /**
     * Store a resolved id so later runs - and the announcement listener - see
     * the concrete value instead of having to repeat the detection.
     */
    private function remember(string $key, int $value): void
    {
        if ($value <= 0) {
            return;
        }

        if ((int) $this->settings->get($key, '') !== $value) {
            $this->settings->set($key, (string) $value);
        }
    }

    private function title(McReport $report): string
    {
        $target = (string) $report->target_name;
        $reporter = trim((string) $report->reporter_name);

        return $reporter === ''
            ? sprintf('[举报] %s', $target)
            : sprintf('[举报] %s（由 %s 提交）', $target, $reporter);
    }

    private function content(McReport $report): string
    {
        $rows = [
            '被举报玩家' => (string) $report->target_name,
            '举报玩家' => trim((string) $report->reporter_name) === ''
                ? '（未提供）'
                : (string) $report->reporter_name,
            '所在服务器' => (string) $report->server_key,
            '提交时间' => (string) $report->created_at?->toDateTimeString(),
            '记录编号' => '#' . $report->id,
        ];

        $lines = ['**举报详情**', ''];

        foreach ($rows as $label => $value) {
            $lines[] = sprintf('- **%s**：%s', $label, $value);
        }

        $lines[] = '';
        $lines[] = '**举报原因**';
        $lines[] = '';
        $lines[] = (string) $report->reason;
        $lines[] = '';
        $lines[] = '---';
        $lines[] = '由游戏内 `/report` 命令自动创建。';

        return implode("\n", $lines);
    }
}
