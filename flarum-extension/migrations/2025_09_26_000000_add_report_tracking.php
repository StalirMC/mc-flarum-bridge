<?php

use Illuminate\Database\Schema\Blueprint;
use Illuminate\Database\Schema\Builder;

/*
 * Adds the two columns the report features need, for installs that already ran an
 * earlier migration:
 *
 *   discussion_id  the forum discussion this report was filed as, so a moderator
 *                  changing that discussion's tags can be traced back to the
 *                  report and to the player who filed it
 *   context        the reported player's own recent public chat, as sent by the
 *                  game server
 *
 * Both are nullable on purpose: every report filed before this migration has
 * neither, and the plugin only sends a transcript when the feature is on.
 *
 * Scope warning, the trap that broke 0.0.12: a PHP closure does NOT inherit the
 * enclosing scope, so `$schema` is undefined inside a Blueprint callback and
 * `php flarum migrate` dies with "Call to a member function hasColumn() on null".
 * Every check therefore runs out here and the callback receives plain booleans.
 */
return [
    'up' => function (Builder $schema) {
        if (! $schema->hasTable('mc_reports')) {
            return;
        }

        $addDiscussionId = ! $schema->hasColumn('mc_reports', 'discussion_id');
        $addContext = ! $schema->hasColumn('mc_reports', 'context');

        if (! $addDiscussionId && ! $addContext) {
            return;
        }

        $schema->table('mc_reports', function (Blueprint $table) use ($addDiscussionId, $addContext) {
            if ($addDiscussionId) {
                $table->unsignedInteger('discussion_id')->nullable()->index();
            }

            if ($addContext) {
                $table->text('context')->nullable();
            }
        });
    },

    'down' => function (Builder $schema) {
        if (! $schema->hasTable('mc_reports')) {
            return;
        }

        $dropDiscussionId = $schema->hasColumn('mc_reports', 'discussion_id');
        $dropContext = $schema->hasColumn('mc_reports', 'context');

        if (! $dropDiscussionId && ! $dropContext) {
            return;
        }

        $schema->table('mc_reports', function (Blueprint $table) use ($dropDiscussionId, $dropContext) {
            if ($dropDiscussionId) {
                $table->dropColumn('discussion_id');
            }

            if ($dropContext) {
                $table->dropColumn('context');
            }
        });
    },
];
