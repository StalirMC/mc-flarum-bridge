<?php

namespace Stalir\McBridge\Api\Controller;

use Flarum\Http\RequestUtil;
use Flarum\User\Exception\NotAuthenticatedException;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McBinding;

/**
 * GET /api/mc-bridge/link
 *
 * Returns the Minecraft binding of the logged-in user, for the forum frontend's
 * settings section.
 */
class LinkStatusController extends AbstractBridgeController
{
    public function handle(ServerRequestInterface $request): ResponseInterface
    {
        $actor = RequestUtil::getActor($request);

        // Answer in this extension's own error shape, exactly like LinkController
        // does. Letting assertRegistered() bubble up yields a Flarum JSON:API
        // envelope instead, which the settings section could only render as a
        // generic "could not read the binding state" - hiding the one thing that
        // mattered, namely that the session was no longer signed in.
        try {
            $actor->assertRegistered();
        } catch (NotAuthenticatedException) {
            return $this->fail('link_login_required', 401);
        }

        $binding = McBinding::with('user')->where('user_id', $actor->id)->first();

        return $this->json([
            'ok' => true,
            'bound' => $binding !== null,
            'binding' => $binding ? $binding->toApiPayload() : null,
        ]);
    }
}
