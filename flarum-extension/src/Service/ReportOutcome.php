<?php

namespace Stalir\McBridge\Service;

use Psr\Log\LoggerInterface;
use Stalir\McBridge\Model\McOutboxMessage;
use Stalir\McBridge\Model\McReport;

/**
 * Marks a report as decided, and tells the reporter about it.
 *
 * The single place anything changes a report's status. There are two ways in -
 * a moderator tagging the report discussion, and Console\ReportCommand - and
 * keeping both behind this method is what stops them drifting apart. It is also
 * what keeps the notification from being queued twice: the status only moves
 * once, and only a move into a decided state notifies.
 */
class ReportOutcome
{
    /** Outbox type the game server renders as "your report was handled". */
    public const TYPE_RESOLVED = 'report_resolved';

    /** Outbox type the game server renders as "your report was rejected". */
    public const TYPE_REJECTED = 'report_rejected';

    public function __construct(
        protected LoggerInterface $log
    ) {
    }

    /**
     * Set a report's status.
     *
     * @param  string  $status  one of McReport::STATUS_*
     * @param  string  $note    optional moderator note, carried to the player
     * @param  bool    $notify  false to change the status silently
     * @return bool true when the status actually changed
     */
    public function apply(McReport $report, string $status, string $note = '', bool $notify = true): bool
    {
        if (! in_array($status, [
            McReport::STATUS_PENDING,
            McReport::STATUS_RESOLVED,
            McReport::STATUS_REJECTED,
        ], true)) {
            return false;
        }

        if ($report->status === $status) {
            // Already there. Anything else would queue a second notification every
            // time a moderator re-saves the same tags, which is easy to do.
            return false;
        }

        $wasClosed = $report->isClosed();

        $report->status = $status;
        $report->save();

        // Only a move into a decided state is worth telling the player about. A
        // re-open back to pending is an internal correction.
        if ($notify && $report->isClosed() && ! $wasClosed) {
            $this->notify($report, $status, $note);
        }

        return true;
    }

    /**
     * Queue the in-game notification for a closed report.
     *
     * Addressed to the reporter's UUID and routed to the server the report came
     * from, so exactly one player is told and no other server picks it up. The
     * wording is not sent: the game server renders it from its own language file,
     * which the forum cannot reach.
     */
    private function notify(McReport $report, string $status, string $note): void
    {
        $message = new McOutboxMessage();
        $message->server_key = $report->server_key;
        $message->type = $status === McReport::STATUS_REJECTED ? self::TYPE_REJECTED : self::TYPE_RESOLVED;
        $message->title = '';
        $message->body = '';
        $message->target_uuid = $report->reporter_uuid;
        $message->payload = [
            'report_id' => $report->id,
            'target' => $report->target_name,
            'status' => $status,
            'note' => $note,
        ];
        $message->save();

        $this->log->info('[mc-bridge] queued a report outcome for its reporter', [
            'report_id' => $report->id,
            'status' => $status,
        ]);
    }
}
