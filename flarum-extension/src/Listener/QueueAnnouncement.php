<?php

namespace Stalir\McBridge\Listener;

use Flarum\Post\Event\Posted;
use Flarum\Settings\SettingsRepositoryInterface;
use Stalir\McBridge\Model\McOutboxMessage;
use Stalir\McBridge\Service\ReportDiscussion;

/**
 * Queues new discussions (and optionally the forum's own replies) for delivery
 * to the game servers.
 *
 * By default only the opening post of a discussion is forwarded, optionally
 * limited to a set of tag IDs, so in-game chat is not flooded by every reply.
 */
class QueueAnnouncement
{
    public function __construct(
        protected SettingsRepositoryInterface $settings
    ) {
    }

    public function handle(Posted $event): void
    {
        $post = $event->post;

        // Only the opening post of a discussion, unless reply syncing is on.
        $syncReplies = filter_var($this->settings->get('mc-bridge.sync_replies', '0'), FILTER_VALIDATE_BOOLEAN);

        if ((int) $post->number !== 1 && ! $syncReplies) {
            return;
        }

        $discussion = $post->discussion;

        if (! $discussion) {
            return;
        }

        // A player report is moderation material and names the reporter, so it
        // must never be pushed into in-game chat.
        if ($this->isModerationDiscussion($discussion, (int) $post->user_id)) {
            return;
        }

        if (! $this->matchesConfiguredTags($discussion)) {
            return;
        }

        $type = (int) $post->number === 1
            ? McOutboxMessage::TYPE_ANNOUNCEMENT
            : McOutboxMessage::TYPE_BROADCAST;

        $message = new McOutboxMessage();
        $message->server_key = null; // deliver to every server
        $message->type = $type;
        $message->title = mb_substr((string) $discussion->title, 0, 255);
        $message->body = $this->excerpt((string) $post->content);
        $message->url = '/d/' . $discussion->id;
        $message->payload = [
            'discussion_id' => $discussion->id,
            'post_id' => $post->id,
            'author' => $post->user?->username,
            'is_op' => (int) $post->number === 1,
        ];
        $message->save();
    }

    /**
     * Is this the discussion the bridge filed for a player report?
     *
     * The tags are the reliable signal, and they are available by the time this
     * runs: the JSON:API create flow runs the field setters first, and the tags
     * field registers a callback that the first `$model->save()` flushes - both
     * of which happen before the opening post is inserted and therefore before
     * Posted is dispatched. Service\ReportDiscussion additionally writes the
     * resolved tag ids back to the settings before it creates anything, so this
     * code always has something to match on.
     *
     * The author check is only a fallback for a forum where no report tag is
     * configured at all, which in practice means flarum/tags is absent and there
     * is no tag to match on. It is deliberately NOT applied otherwise: the report
     * author defaults to the oldest administrator, so treating "posted by that
     * account" as "this is a report" silenced every ordinary announcement made by
     * that administrator.
     *
     * A false negative here hands the report, and the reporter's name, to every
     * player online; a false positive silently drops a normal announcement.
     */
    private function isModerationDiscussion($discussion, int $authorId): bool
    {
        $reportTagIds = $this->settingIds(ReportDiscussion::TAGS_SETTING);

        if ($reportTagIds !== []) {
            try {
                // Any of them is enough: a report is filed under the whole list, and
                // a moderator may add or remove one afterwards.
                return $discussion->tags()->whereIn('id', $reportTagIds)->exists();
            } catch (\Throwable) {
                // flarum/tags is not installed.
                return false;
            }
        }

        return in_array($authorId, $this->settingIds(ReportDiscussion::ACTOR_SETTING), true);
    }

    /**
     * Read a comma separated id setting as a list of positive integers.
     *
     * @return array<int, int>
     */
    private function settingIds(string $key): array
    {
        $ids = [];

        foreach (explode(',', (string) $this->settings->get($key, '')) as $part) {
            $part = trim($part);

            if ($part !== '' && ctype_digit($part) && (int) $part > 0) {
                $ids[] = (int) $part;
            }
        }

        return $ids;
    }

    /**
     * Determine whether the discussion is covered by the configured tag filter.
     * An empty filter means "every discussion".
     */
    private function matchesConfiguredTags($discussion): bool
    {
        $configured = array_filter(array_map(
            'intval',
            explode(',', (string) $this->settings->get('mc-bridge.announcement_tag_ids', ''))
        ));

        if ($configured === []) {
            return true;
        }

        try {
            $tagIds = $discussion->tags()->pluck('id')->all();
        } catch (\Throwable) {
            // flarum/tags is not installed: fall back to syncing everything.
            return true;
        }

        return array_intersect($configured, array_map('intval', $tagIds)) !== [];
    }

    /**
     * Flatten post content into a short, plain-text preview for in-game chat.
     */
    private function excerpt(string $content, int $limit = 300): string
    {
        $text = preg_replace('/\[[^\]]*\]/', ' ', $content) ?? $content;
        $text = strip_tags($text);
        $text = preg_replace('/\s+/u', ' ', $text) ?? $text;
        $text = trim($text);

        if (mb_strlen($text) > $limit) {
            $text = rtrim(mb_substr($text, 0, $limit)) . '…';
        }

        return $text;
    }
}
