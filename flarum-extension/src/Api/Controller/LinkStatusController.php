<?php

namespace Stalir\McBridge\Api\Controller;

use Flarum\Http\RequestUtil;
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
        $actor->assertRegistered();

        $binding = McBinding::with('user')->where('user_id', $actor->id)->first();

        return $this->json([
            'ok' => true,
            'bound' => $binding !== null,
            'binding' => $binding ? $binding->toApiPayload() : null,
        ]);
    }
}
