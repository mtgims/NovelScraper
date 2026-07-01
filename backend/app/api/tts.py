"""Text-to-speech status/voices (optional feature)."""

from __future__ import annotations

from fastapi import APIRouter

from ..tts import DEFAULT_VOICE, tts

router = APIRouter()


@router.get("/voices")
def voices():
    """Whether TTS is available and the list of voices. The frontend hides the
    Listen button when available is false."""
    available = tts.available()
    return {
        "available": available,
        "default": DEFAULT_VOICE,
        "voices": tts.voices() if available else [],
        "device": tts.device() if available else "unknown",
    }
