<?php

namespace Stalir\McBridge\Listener;

use Flarum\Settings\SettingsRepositoryInterface;
use Flarum\Tags\Event\DiscussionWasTagged;
use Psr\Log\LoggerInterface;
use Stalir\McBridge\Model\McReport;
use Stalir\McBridge\Service\ReportDiscussion;
use Stalir\McBridge\Service\ReportOutcome;

/**
 * Turns a moderator's tag edit into a report outcome.
 *
 * When a moderator moves a report discussion into the forum's "resolved" or
 * "rejected" tag, the player who filed the report hears about it in game. This is
 * the automatic half of the feature; Console\ReportCommand is the deliberate
 * half, for reports nobody tags.
 *
 * The new tags ARE readable here, which is the one subtle thing about this
 * listener. Flarum's JSON:API pipeline runs the field setters first and releases
 * the model's pending events only afterwards: HasHooks::updateAction calls
 * update() (which saves and syncs the tag pivot) and then dispatchEventsFor(), so
 * by the time this runs the pivot is written. That is the same ordering the
 * framework's own CreatePostWhenTagsAreChanged relies on when it records the new
 * tag ids for its "tags changed" post.
 */
class ReportTagListener
{
    public function __construct(
        protected SettingsRepositoryInterface $settings,
        protected ReportOutcome $outcome,
        protected LoggerInterface $log
    ) {
    }

    public function handle(DiscussionWasTagged $event): void
    {
        $discussionId = (int) $event->discussion->id;

        $report = McReport::query()->where('discussion_id', $discussionId)->first();

        if ($report === null) {
            // Not one of ours. This is the overwhelmingly common case: every tag
            // edit anywhere on the forum reaches this listener.
            return;
        }

        if ($report->isClosed()) {
            // Already decided. Leaving it alone is what makes this idempotent, and
            // it is why a moderator can tidy up the tags afterwards without
            // sending the player a second notice.
            return;
        }

        $status = $this->statusFor($this->currentTagIds($event));

        if ($status === null) {
            return;
        }

        // No note on this path: tagging a discussion carries no explanation, and
        // inventing one would put words in the moderator's mouth. The console
        // command has --note for when one is actually wanted.
        if (! $this->outcome->apply($report, $status)) {
            return;
        }

        $this->log->info('[mc-bridge] a moderator tagged a report discussion', [
            'report_id' => $report->id,
            'discussion_id' => $discussionId,
            'status' => $status,
        ]);
    }

    /**
     * Which status the discussion's current tags imply, if any.
     *
     * Rejected is checked first on purpose: when a moderator adds "rejected"
     * without removing a stale "resolved", both lists match, and the more specific
     * answer is the rejection.
     *
     * @param  array<int, int>  $tagIds
     */
    private function statusFor(array $tagIds): ?string
    {
        if (array_intersect($this->settingIds(ReportDiscussion::REJECTED_TAGS_SETTING), $tagIds) !== []) {
            return McReport::STATUS_REJECTED;
        }

        if (array_intersect($this->settingIds(ReportDiscussion::RESOLVED_TAGS_SETTING), $tagIds) !== []) {
            return McReport::STATUS_RESOLVED;
        }

        return null;
    }

    /**
     * The tag ids on the discussion right now.
     *
     * Read back from the query builder rather than from the relation, so the
     * values come from the database after the sync instead of from a stale
     * in-memory relation.
     *
     * @return array<int, int>
     */
    private function currentTagIds(DiscussionWasTagged $event): array
    {
        try {
            return array_map('intval', $event->discussion->tags()->pluck('id')->all());
        } catch (\Throwable) {
            // flarum/tags is not installed, in which case this event cannot fire at
            // all - but it costs nothing to be certain.
            return [];
        }
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
}
