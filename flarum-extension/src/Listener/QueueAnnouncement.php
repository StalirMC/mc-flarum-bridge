<?php

namespace Stalir\McBridge\Listener;

use Flarum\Post\Event\Posted;
use Flarum\Settings\SettingsRepositoryInterface;
use Stalir\McBridge\Model\McOutboxMessage;

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
