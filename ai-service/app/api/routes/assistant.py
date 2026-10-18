"""POST /api/v1/ai/assistant/compose - JanNet Sahayak v1 wording (internal only).

Called by the backend's AssistantService for open questions and guided filing.
Always answers 200: when Gemini is not configured, busy, failing or cannot
ground an answer, ``answered`` is false and the backend falls back to its own
rule-based reply.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends

from app.api.deps import require_internal_api_key
from app.schemas.assistant import ComposeRequest, ComposeResponse
from app.schemas.common import ErrorResponse
from app.services import assistant_service

router = APIRouter(prefix="/api/v1/ai/assistant", tags=["assistant"])


@router.post(
    "/compose",
    response_model=ComposeResponse,
    status_code=200,
    dependencies=[Depends(require_internal_api_key)],
    responses={401: {"model": ErrorResponse, "description": "Missing/invalid internal API key"}},
    summary="Word an assistant answer from the given sources, or draft a complaint description",
)
async def compose(request: ComposeRequest) -> ComposeResponse:
    return await assistant_service.compose(request)
