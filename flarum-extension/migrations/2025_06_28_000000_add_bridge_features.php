<?php

use Illuminate\Database\Schema\Blueprint;
use Illuminate\Database\Schema\Builder;

/*
 * Incremental migration for installs that already ran the 2025_01_01 create
 * migration before the report / activity features existed.
 *
 * The create migration is never re-run on an existing forum (Flarum records it
 * as executed), so the three new tables and the two new outbox columns must be
 * added here instead. Every step is guarded so a fresh install - where the
 * create migration already produced all of this - is a no-op.
 *
 * Flarum 2.x migrations must RETURN an array of closures; the schema builder is
 * passed as the first argument.
 */
return [
    'up' => function (Builder $schema) {
        // mc_outbox gained target_uuid (per-player routing) and actor_id
        // (broadcast audit trail).
        if ($schema->hasTable('mc_outbox')) {
            $schema->table('mc_outbox', function (Blueprint $table) {
                if (! $schema->hasColumn('mc_outbox', 'target_uuid')) {
                    $table->string('target_uuid', 36)->nullable()->index();
                }

                if (! $schema->hasColumn('mc_outbox', 'actor_id')) {
                    $table->unsignedInteger('actor_id')->nullable();
                }
            });
        }

        if (! $schema->hasTable('mc_reports')) {
            $schema->create('mc_reports', function (Blueprint $table) {
                $table->increments('id');
                $table->string('server_key', 100)->index();
                $table->string('reporter_uuid', 36)->index();
                $table->string('reporter_name', 64)->nullable();
                $table->string('target_name', 64)->index();
                $table->text('reason');
                $table->string('status', 20)->default('pending')->index();
                $table->timestamps();
            });
        }

        if (! $schema->hasTable('mc_activities')) {
            $schema->create('mc_activities', function (Blueprint $table) {
                $table->increments('id');
                // A null server_key means "ask every server".
                $table->string('server_key', 100)->nullable()->index();
                $table->string('title', 255);
                // Array of option labels, in display order.
                $table->json('options');
                $table->dateTime('closes_at');
                $table->boolean('closed')->default(false)->index();
                // Set once the results have been queued back to the game.
                $table->dateTime('announced_at')->nullable();
                $table->timestamps();
            });
        }

        if (! $schema->hasTable('mc_votes')) {
            $schema->create('mc_votes', function (Blueprint $table) {
                $table->increments('id');
                $table->unsignedInteger('activity_id')->index();
                $table->string('player_uuid', 36)->index();
                $table->string('player_name', 64)->nullable();
                $table->unsignedInteger('option_index');
                $table->timestamps();

                // One vote per player per activity; re-voting replaces it.
                $table->unique(['activity_id', 'player_uuid']);
            });
        }
    },

    'down' => function (Builder $schema) {
        $schema->dropIfExists('mc_votes');
        $schema->dropIfExists('mc_activities');
        $schema->dropIfExists('mc_reports');

        if ($schema->hasTable('mc_outbox')) {
            $schema->table('mc_outbox', function (Blueprint $table) {
                if ($schema->hasColumn('mc_outbox', 'actor_id')) {
                    $table->dropColumn('actor_id');
                }

                if ($schema->hasColumn('mc_outbox', 'target_uuid')) {
                    $table->dropColumn('target_uuid');
                }
            });
        }
    },
];
