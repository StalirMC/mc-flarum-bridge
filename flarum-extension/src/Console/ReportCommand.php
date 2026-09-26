<?php

namespace Stalir\McBridge\Console;

use Flarum\Settings\SettingsRepositoryInterface;
use Stalir\McBridge\Model\McReport;
use Stalir\McBridge\Service\BridgeMessages;
use Stalir\McBridge\Service\ReportOutcome;
use Symfony\Component\Console\Input\InputArgument;
use Symfony\Component\Console\Input\InputOption;

/**
 * php flarum mc-bridge:report --list
 * php flarum mc-bridge:report 12 --status=resolved --note="已警告该玩家"
 *
 * Lists player reports, or records what happened to one. Recording it is what
 * tells the player in game, so this is the deliberate half of the outcome
 * feature: Listener\ReportTagListener is the automatic half, for the reports a
 * moderator closes by moving the discussion into a "resolved" tag.
 *
 * Both go through Service\ReportOutcome, so a report closed here and a report
 * closed by tagging behave identically - including only ever notifying once.
 */
class ReportCommand extends AbstractBridgeCommand
{
    public function __construct(
        SettingsRepositoryInterface $settings,
        BridgeMessages $messages,
        protected ReportOutcome $outcome
    ) {
        parent::__construct($settings, $messages);
    }

    protected function configure(): void
    {
        parent::configure();

        $this
            ->setName('mc-bridge:report')
            ->setDescription('List player reports, or record what happened to one')
            ->addArgument('id', InputArgument::OPTIONAL, 'Report id, as shown by --list')
            ->addOption('list', null, InputOption::VALUE_NONE, 'List the most recent reports')
            ->addOption('limit', null, InputOption::VALUE_REQUIRED, 'How many reports --list shows (default 20)')
            ->addOption(
                'status',
                null,
                InputOption::VALUE_REQUIRED,
                'New status: pending, resolved or rejected'
            )
            ->addOption('note', null, InputOption::VALUE_REQUIRED, 'Note carried to the reporter')
            ->addOption('silent', null, InputOption::VALUE_NONE, 'Change the status without telling the reporter');
    }

    protected function fire(): int
    {
        // With no id there is nothing to change, so show the list instead of
        // failing: that is what the person typing this most likely wanted.
        if ($this->option('list') || $this->argument('id') === null) {
            return $this->showList();
        }

        return $this->update();
    }

    private function showList(): int
    {
        $limit = (int) ($this->option('limit') ?? 20);
        $limit = max(1, min(100, $limit));

        $reports = McReport::query()->orderByDesc('id')->limit($limit)->get();

        $this->line('');
        $this->info($this->messages->get('console.report.list_title', ['count' => (string) $reports->count()]));
        $this->line('');

        if ($reports->isEmpty()) {
            $this->comment($this->messages->get('console.report.list_empty'));
            $this->line('');

            return self::SUCCESS;
        }

        foreach ($reports as $report) {
            $this->line(sprintf(
                '  #%-5s %-9s %-16s %s',
                (string) $report->id,
                (string) $report->status,
                (string) $report->target_name,
                (string) $report->created_at?->toDateTimeString()
            ));
        }

        $this->line('');
        $this->comment($this->messages->get('console.report.list_hint'));

        return self::SUCCESS;
    }

    private function update(): int
    {
        $id = (int) $this->argument('id');

        $statuses = [McReport::STATUS_PENDING, McReport::STATUS_RESOLVED, McReport::STATUS_REJECTED];
        $status = trim((string) ($this->option('status') ?? ''));

        if (! in_array($status, $statuses, true)) {
            $this->error($this->messages->get('console.report.status_required', [
                'statuses' => implode(', ', $statuses),
            ]));

            return self::FAILURE;
        }

        $report = McReport::find($id);

        if ($report === null) {
            $this->error($this->messages->get('console.report.not_found', ['id' => (string) $id]));

            return self::FAILURE;
        }

        $notify = ! $this->option('silent');
        $note = trim((string) ($this->option('note') ?? ''));

        if (! $this->outcome->apply($report, $status, $note, $notify)) {
            // Already in that state, or the status did not move. Reported as a
            // comment rather than an error: asking for the state it is already in
            // is not a failure.
            $this->comment($this->messages->get('console.report.unchanged', [
                'id' => (string) $report->id,
                'status' => (string) $report->status,
            ]));

            return self::SUCCESS;
        }

        $this->info($this->messages->get('console.report.updated', [
            'id' => (string) $report->id,
            'status' => $status,
        ]));

        if ($notify && $report->isClosed()) {
            $this->comment($this->messages->get('console.report.notified', [
                'target' => (string) $report->target_name,
            ]));
        }

        return self::SUCCESS;
    }
}
