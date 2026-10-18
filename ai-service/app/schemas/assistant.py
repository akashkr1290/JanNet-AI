"""Request/response shapes for POST /api/v1/ai/assistant/compose (JanNet Sahayak v1).

The backend is the only caller. It has already decided WHAT the assistant may
say: ``facts`` are results of the backend's own look-ups for the signed-in user
(for example one complaint's status) and ``articles`` are help articles from the
database. This service only words an answer from them, or drafts a complaint
description (mode ``draft_report``). Personal data is removed by the backend
before the call and removed again here.
"""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

LanguageCode = Literal["EN", "HI", "MR", "BN", "TA", "TE", "KN"]


class AssistantFact(BaseModel):
    source: str = Field(min_length=1, max_length=80, description="Id the answer cites, e.g. complaint:JN-2026-000123")
    text: str = Field(min_length=1, max_length=1500)


class AssistantArticle(BaseModel):
    slug: str = Field(min_length=1, max_length=60, pattern=r"^[a-z0-9-]+$")
    title: str = Field(min_length=1, max_length=200)
    body: str = Field(min_length=1, max_length=2000)


class ComposeRequest(BaseModel):
    mode: Literal["answer", "draft_report"] = "answer"
    question: str = Field(min_length=1, max_length=1000)
    language: LanguageCode = "EN"
    facts: list[AssistantFact] = Field(default_factory=list, max_length=10)
    articles: list[AssistantArticle] = Field(default_factory=list, max_length=5)


class ComposeResponse(BaseModel):
    used_llm: bool
    answered: bool = Field(description="True only when the reply is grounded in at least one given source "
                                       "(answer mode) or a civic problem was recognised (draft_report mode).")
    reply: str | None = None
    sources: list[str] = Field(default_factory=list, description="Ids from facts/articles the reply is based on.")
    category: str | None = Field(default=None, description="draft_report: one of the supported categories.")
    description: str | None = Field(default=None, description="draft_report: cleaned complaint description.")
    fallback_reason: str | None = None
