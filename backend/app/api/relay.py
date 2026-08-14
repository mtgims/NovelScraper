"""WebSocket endpoint the native app connects to so scrapes fetch via the user's
IP. Authenticated by the session cookie; registers the connection in the relay
hub for the duration, and pumps reply frames back to the waiting fetches."""

from __future__ import annotations

import logging

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from sqlmodel import Session

from ..auth import COOKIE_NAME, resolve_session
from ..db import engine
from ..relay import RelayConnection, hub

logger = logging.getLogger(__name__)

router = APIRouter()


@router.websocket("/api/relay")
async def relay_ws(ws: WebSocket) -> None:
    # Authenticate from the session cookie sent on the WS handshake (same cookie
    # the app uses for the REST API).
    token = ws.cookies.get(COOKIE_NAME, "")
    with Session(engine) as session:
        user = resolve_session(session, token)
        # Read the id inside the session — the User detaches when it closes.
        user_id = user.id if user is not None else None
    if user_id is None:
        await ws.close(code=1008)  # policy violation / unauthorized
        return

    await ws.accept()
    conn = RelayConnection(ws)
    hub.register(user_id, conn)
    logger.info("relay connected: user=%s", user_id)
    try:
        while True:
            reply = await ws.receive_json()
            conn.resolve(reply)
    except WebSocketDisconnect:
        pass
    except Exception as e:  # noqa: BLE001 - any receive error just ends the relay
        logger.debug("relay receive ended (user=%s): %s", user_id, e)
    finally:
        hub.unregister(user_id, conn)
        conn.fail_all()
        logger.info("relay disconnected: user=%s", user_id)
