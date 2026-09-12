<?php

use Illuminate\Database\Schema\Blueprint;
use Illuminate\Database\Schema\Builder;

/*
 * Flarum 2.x migrations must RETURN an array of closures; the schema builder is
 * passed as the first argument. The Flarum 1.x style (a class extending the
 * Migration base and reading a schema property off the instance) no longer
 * works: in 2.x that base class only offers static helpers and has no schema
 * property, so the instance property would be null and `migrate` would fatal.
 */
return [
    'up' => function (Builder $schema) {
        if (! $schema->hasTable('mc_servers')) {
            $schema->create('mc_servers', function (Blueprint $table) {
                $table->increments('id');
                $table->string('server_key', 100)->unique();
                $table->boolean('online')->default(false);
                $table->unsignedInteger('players_online')->default(0);
                $table->unsignedInteger('players_max')->default(0);
                $table->float('tps')->nullable();
                $table->float('mspt')->nullable();
                $table->string('version', 64)->nullable();
                $table->string('motd', 255)->nullable();
                $table->json('player_names')->nullable();
                $table->dateTime('last_heartbeat_at')->nullable();
                $table->timestamps();
            });
        }

        if (! $schema->hasTable('mc_events')) {
            $schema->create('mc_events', function (Blueprint $table) {
                $table->increments('id');
                $table->string('server_key', 100);
                $table->string('type', 40);
                $table->string('player_uuid', 36)->nullable();
                $table->string('player_name', 64)->nullable();
                $table->text('message')->nullable();
                $table->dateTime('happened_at')->nullable();
                $table->timestamps();

                $table->index(['server_key', 'created_at']);
                $table->index('player_uuid');
            });
        }

        if (! $schema->hasTable('mc_outbox')) {
            $schema->create('mc_outbox', function (Blueprint $table) {
                $table->increments('id');
                // A null server_key means "deliver to every server".
                $table->string('server_key', 100)->nullable();
                $table->string('type', 40)->default('announcement');
                $table->string('title', 255)->nullable();
                $table->text('body')->nullable();
                $table->string('url', 255)->nullable();
                $table->json('payload')->nullable();
                $table->dateTime('delivered_at')->nullable();
                $table->timestamps();

                $table->index(['server_key', 'delivered_at']);
            });
        }

        if (! $schema->hasTable('mc_bindings')) {
            $schema->create('mc_bindings', function (Blueprint $table) {
                $table->increments('id');
                $table->unsignedInteger('user_id')->unique();
                // unique() already provides an index for equality lookups.
                $table->string('player_uuid', 36)->unique();
                $table->string('player_name', 64)->nullable();
                $table->string('server_key', 100)->nullable();
                $table->timestamps();
            });
        }

        if (! $schema->hasTable('mc_bind_codes')) {
            $schema->create('mc_bind_codes', function (Blueprint $table) {
                $table->increments('id');
                $table->string('code', 32)->unique();
                $table->string('player_uuid', 36)->index();
                $table->string('player_name', 64)->nullable();
                $table->string('server_key', 100)->nullable();
                $table->unsignedInteger('user_id')->nullable();
                $table->dateTime('expires_at');
                $table->dateTime('used_at')->nullable();
                $table->timestamps();
            });
        }
    },

    'down' => function (Builder $schema) {
        $schema->dropIfExists('mc_bind_codes');
        $schema->dropIfExists('mc_bindings');
        $schema->dropIfExists('mc_outbox');
        $schema->dropIfExists('mc_events');
        $schema->dropIfExists('mc_servers');
    },
];
