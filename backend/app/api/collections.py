"""Library collections (Mihon-style categories) — CRUD.

Membership (which books are in a collection) lives on the books router, alongside
reordering, since those operate on books. Collections here are just the groups.
"""

from __future__ import annotations

from typing import List

from fastapi import APIRouter, Depends, HTTPException
from sqlmodel import Session, delete, select

from ..db import get_session
from ..models import BookCollectionLink, Collection, User
from ..schemas import CollectionCreate, CollectionRead, CollectionUpdate
from .deps import get_current_user

router = APIRouter()


def _owned_collection(session: Session, collection_id: int, user: User) -> Collection:
    coll = session.get(Collection, collection_id)
    if coll is None or coll.user_id != user.id:
        raise HTTPException(status_code=404, detail="Collection not found")
    return coll


@router.get("", response_model=List[CollectionRead])
def list_collections(user: User = Depends(get_current_user),
                     session: Session = Depends(get_session)):
    return session.exec(
        select(Collection).where(Collection.user_id == user.id)
        .order_by(Collection.sort_order, Collection.id)
    ).all()


@router.post("", response_model=CollectionRead, status_code=201)
def create_collection(body: CollectionCreate, user: User = Depends(get_current_user),
                      session: Session = Depends(get_session)):
    # New collection goes to the end of the caller's tab bar.
    last = session.exec(
        select(Collection).where(Collection.user_id == user.id)
        .order_by(Collection.sort_order.desc())
    ).first()
    coll = Collection(name=body.name.strip(), user_id=user.id,
                      sort_order=(last.sort_order + 1) if last else 0)
    session.add(coll)
    session.commit()
    session.refresh(coll)
    return coll


@router.patch("/{collection_id}", response_model=CollectionRead)
def update_collection(collection_id: int, body: CollectionUpdate,
                      user: User = Depends(get_current_user),
                      session: Session = Depends(get_session)):
    coll = _owned_collection(session, collection_id, user)
    if body.name is not None:
        coll.name = body.name.strip()
    if body.sort_order is not None:
        coll.sort_order = body.sort_order
    session.add(coll)
    session.commit()
    session.refresh(coll)
    return coll


@router.delete("/{collection_id}", status_code=204)
def delete_collection(collection_id: int, user: User = Depends(get_current_user),
                      session: Session = Depends(get_session)):
    coll = _owned_collection(session, collection_id, user)
    # Remove memberships but keep the books themselves.
    session.exec(
        delete(BookCollectionLink).where(
            BookCollectionLink.collection_id == collection_id
        )
    )
    session.delete(coll)
    session.commit()
