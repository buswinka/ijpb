// Worker entry. Routes POST requests to endpoint handlers.
// GET / returns a simple health check.

import type { Env } from './env';
import { errorResponse } from './errors';
import { handleStatus }   from './endpoints/status';
import { handleActivate } from './endpoints/activate';
import { handleChat }     from './endpoints/chat';

export default {
    async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
        const url = new URL(req.url);

        if (req.method === 'GET' && url.pathname === '/') {
            return new Response('imagej-llm-backend ok', { status: 200 });
        }

        if (req.method !== 'POST') {
            return errorResponse('bad_request', 'only POST is supported', 405);
        }

        try {
            switch (url.pathname) {
                case '/status':   return await handleStatus(req, env);
                case '/activate': return await handleActivate(req, env);
                case '/chat':     return await handleChat(req, env, ctx);
                default:
                    return errorResponse('bad_request', `unknown path ${url.pathname}`, 404);
            }
        } catch (err) {
            console.error('unhandled error', err);
            return errorResponse('internal_error', 'something went wrong', 500);
        }
    },
};
