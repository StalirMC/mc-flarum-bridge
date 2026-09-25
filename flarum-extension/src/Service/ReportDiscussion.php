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
    /** Comma separated tag ids. When empty, tags are detected by slug/name. */
    public const TAGS_SETTING = 'mc-bridge.report_tag_ids';

    /** Explicit author. When empty, the oldest administrator is used. */
    public const ACTOR_SETTING = 'mc-bridge.report_actor_id';

    /**
     * Title template used when the game server does not send one.
     *
     * Tokens: {target} {reporter} {reason} {server}.
     */
    public const TITLE_SETTING = 'mc-bridge.report_title_format';

    public const DEFAULT_TITLE_FORMAT = '[举报] {target}（由 {reporter} 提交）';

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
     * @param  array{title?: string|null, tags?: array<int, string>|null, actor?: string|null}  $overrides
     *         Layout hints from the game server's config.yml. Whatever is absent
     *         or cannot be resolved falls back to this forum's own settings, so a
     *         server that sends nothing behaves exactly as before.
     * @return int|null the new discussion id, or null when it could not be made
     */
    public function create(McReport $report, array $overrides = []): ?int
    {
        try {
            return $this->dispatch($report, $overrides);
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

    private function dispatch(McReport $report, array $overrides): ?int
    {
        $actor = $this->actor($overrides['actor'] ?? null);

        if ($actor === null) {
            $this->log->warning(
                '[mc-bridge] no administrator account found to author the report discussion; ' .
                'set one with: php flarum mc-bridge:config --report-actor=<user id>'
            );

            return null;
        }

        $tags = $this->tags($overrides['tags'] ?? null);

        // Remember what is actually in use. This is not cosmetic: the announcement
        // listener decides whether a discussion is moderation material from these
        // two settings, so a value that only existed for one request would let the
        // report - and the reporter's name - be broadcast into in-game chat.
        $this->rememberIds(self::ACTOR_SETTING, [(int) $actor->id]);
        $this->rememberIds(self::TAGS_SETTING, array_map(
            static fn (Tag $tag) => (int) $tag->id,
            $tags
        ));

        // The game server's title wins when it sends one: it may contain
        // PlaceholderAPI values that only exist in game.
        $title = trim((string) ($overrides['title'] ?? ''));

        if ($title === '') {
            $title = $this->renderTitle($report);
        }

        $data = [
            'type' => 'discussions',
            'attributes' => [
                'title' => $title,
                'content' => $this->content($report),
            ],
        ];

        if ($tags !== []) {
            // The tags resource only exists when flarum/tags is enabled, and an
            // unknown relationship fails validation, so the key is only added
            // when at least one tag was resolved.
            //
            // Note that flarum/tags enforces how many primary and secondary tags a
            // discussion may carry. Exceeding that limit makes the create call
            // throw, which is logged below with the way out.
            $data['relationships'] = [
                'tags' => [
                    'data' => array_map(
                        static fn (Tag $tag) => ['type' => 'tags', 'id' => (string) $tag->id],
                        $tags
                    ),
                ],
            ];

            foreach ($tags as $tag) {
                if ($actor->cannot('startDiscussion', $tag)) {
                    $this->log->error(
                        '[mc-bridge] the report author may not start discussions in every report ' .
                        'tag, so the discussion was not created',
                        ['tag_id' => $tag->id, 'user_id' => $actor->id]
                    );

                    return null;
                }
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
     * discussion in the report tags, and filing the report as the reporter would
     * publish their identity to everyone who can read them.
     *
     * @param  string|null  $hint  username or id from the game server's config.yml
     */
    private function actor(?string $hint): ?User
    {
        if ($hint !== null) {
            $user = ctype_digit($hint)
                ? User::find((int) $hint)
                : User::where('username', $hint)->first();

            if ($user !== null) {
                return $user;
            }

            $this->log->warning(
                '[mc-bridge] the report author configured on the game server does not exist on this ' .
                'forum; falling back to the setting here',
                ['actor' => $hint]
            );
        }

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
     * Every tag the report is filed under, in the order they should appear.
     *
     * The game server's hints win; then this forum's own setting; otherwise a tag
     * with the conventional slug is adopted, which is how the tag on an existing
     * forum gets used without any configuration. When flarum/tags is enabled but
     * no report tag exists at all, one is created: discussions require at least
     * one tag unless the actor may bypass tag counts, so without it the report
     * could not be filed.
     *
     * @param  array<int, string>|null  $hints  slugs or ids from the game server
     * @return array<int, Tag>
     */
    private function tags(?array $hints): array
    {
        if (! class_exists(Tag::class)) {
            return [];
        }

        $tags = [];

        foreach ($hints ?? [] as $hint) {
            $tag = $this->resolveTag($hint);

            if ($tag === null) {
                $this->log->warning(
                    '[mc-bridge] a report tag configured on the game server does not exist on this ' .
                    'forum; skipping it',
                    ['tag' => $hint]
                );

                continue;
            }

            $tags[$tag->id] = $tag;
        }

        if ($tags !== []) {
            return array_values($tags);
        }

        foreach ($this->splitIds((string) $this->settings->get(self::TAGS_SETTING, '')) as $id) {
            $tag = Tag::find($id);

            if ($tag !== null) {
                $tags[$tag->id] = $tag;
            }
        }

        if ($tags !== []) {
            return array_values($tags);
        }

        $existing = Tag::where('slug', self::TAG_SLUG)->first()
            ?? Tag::where('name', self::TAG_NAME)->first();

        if ($existing !== null) {
            return [$existing];
        }

        $tag = Tag::build(self::TAG_NAME, self::TAG_SLUG, '', '#e9fe48', 'fas fa-exclamation-triangle', false);
        // Secondary tag on purpose: it must not push its way into the primary
        // navigation of a forum that never asked for it.
        $tag->is_primary = false;
        $tag->save();

        $this->log->info(
            '[mc-bridge] created the report tag; point the bridge elsewhere with: ' .
            'php flarum mc-bridge:config --report-tags=<tag ids>',
            ['tag_id' => $tag->id]
        );

        return [$tag];
    }

    /** Resolve one slug or id; null when no such tag exists here. */
    private function resolveTag(string $hint): ?Tag
    {
        return ctype_digit($hint)
            ? Tag::find((int) $hint)
            : Tag::where('slug', $hint)->first();
    }

    /**
     * Store resolved ids so later runs - and the announcement listener - see the
     * concrete values instead of having to repeat the detection.
     *
     * @param  array<int, int>  $ids
     */
    private function rememberIds(string $key, array $ids): void
    {
        $ids = array_values(array_unique(array_filter($ids, static fn (int $id) => $id > 0)));
        $stored = implode(',', $ids);

        if ($stored !== '' && (string) $this->settings->get($key, '') !== $stored) {
            $this->settings->set($key, $stored);
        }
    }

    /**
     * Parse a comma separated id setting into a list of positive integers.
     *
     * @return array<int, int>
     */
    private function splitIds(string $raw): array
    {
        $ids = [];

        foreach (explode(',', $raw) as $part) {
            $part = trim($part);

            if ($part !== '' && ctype_digit($part) && (int) $part > 0) {
                $ids[] = (int) $part;
            }
        }

        return $ids;
    }

    /**
     * Render the title from this forum's template.
     *
     * Only used when the game server sent no title of its own: that template is
     * preferred because it may contain PlaceholderAPI values that exist only in
     * game.
     */
    private function renderTitle(McReport $report): string
    {
        $format = trim((string) $this->settings->get(self::TITLE_SETTING, self::DEFAULT_TITLE_FORMAT));

        if ($format === '') {
            $format = self::DEFAULT_TITLE_FORMAT;
        }

        $reporter = trim((string) $report->reporter_name);

        $title = trim(strtr($format, [
            '{target}' => (string) $report->target_name,
            '{reporter}' => $reporter === '' ? '（未提供）' : $reporter,
            '{reason}' => (string) $report->reason,
            '{server}' => (string) $report->server_key,
        ]));

        // discussions.title is 255 characters and cannot be empty, so an
        // over-long template is cut and a template that renders to nothing falls
        // back to something that still identifies the report.
        if ($title === '') {
            $title = sprintf('[举报] %s', (string) $report->target_name);
        }

        return mb_substr($title, 0, 255);
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
