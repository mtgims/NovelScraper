"use client";

import {
  Check,
  Copy,
  Plus,
  Shield,
  ShieldOff,
  UserCheck,
  UserX,
} from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import {
  useCreateInvite,
  useInvites,
  useMe,
  useSettings,
  useUpdateSettings,
  useUpdateUser,
  useUsers,
} from "@/lib/queries";
import type { Invite } from "@/lib/types";
import { cn } from "@/lib/utils";

function inviteStatus(inv: Invite): "used" | "expired" | "active" {
  if (inv.used_by != null) return "used";
  if (new Date(inv.expires_at).getTime() < Date.now()) return "expired";
  return "active";
}

export function AdminPanel() {
  const { data: me } = useMe();
  const { data: settings } = useSettings();
  const updateSettings = useUpdateSettings();
  const { data: invites } = useInvites();
  const createInvite = useCreateInvite();
  const { data: users } = useUsers();
  const updateUser = useUpdateUser();
  const [copied, setCopied] = useState<string | null>(null);

  const openSignup = settings?.allow_open_signup ?? false;

  async function copy(code: string) {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(code);
      setTimeout(() => setCopied((c) => (c === code ? null : c)), 1500);
    } catch {
      /* clipboard unavailable (non-secure context) — the code is visible anyway */
    }
  }

  return (
    <section className="mt-14 max-w-xl">
      <div className="rule-accent pt-3">
        <h2 className="font-display text-2xl">Admin</h2>
        <p className="mt-1 mb-4 text-sm text-muted-foreground">
          Control sign-up, hand out invites, and manage accounts.
        </p>
      </div>

      {/* Open sign-up toggle */}
      <Card className="flex items-center justify-between gap-4 p-4">
        <div className="min-w-0">
          <p className="text-sm font-medium">Open sign-up</p>
          <p className="text-xs text-muted-foreground">
            {openSignup
              ? "Anyone who can reach the app can register."
              : "Registration requires an invite code."}
          </p>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={openSignup}
          aria-label="Open sign-up"
          disabled={updateSettings.isPending}
          onClick={() => updateSettings.mutate({ allow_open_signup: !openSignup })}
          className={cn(
            "relative h-6 w-11 shrink-0 rounded-full transition-colors",
            openSignup ? "bg-accent" : "bg-muted"
          )}
        >
          <span
            className={cn(
              "absolute top-0.5 h-5 w-5 rounded-full bg-background shadow transition-transform",
              openSignup ? "translate-x-[1.375rem]" : "translate-x-0.5"
            )}
          />
        </button>
      </Card>

      {/* Invites */}
      <div className="mt-8 flex items-center justify-between">
        <h3 className="font-display text-lg">Invites</h3>
        <Button
          size="sm"
          onClick={() => createInvite.mutate()}
          disabled={createInvite.isPending}
        >
          <Plus size={15} /> New invite
        </Button>
      </div>
      <Card className="mt-3 divide-y divide-border overflow-hidden">
        {(invites ?? []).length === 0 ? (
          <p className="px-4 py-3 text-sm text-muted-foreground">
            No invites yet. Generate one and share the code.
          </p>
        ) : (
          invites!.map((inv) => {
            const status = inviteStatus(inv);
            return (
              <div
                key={inv.code}
                className="flex items-center justify-between gap-3 px-4 py-3"
              >
                <code className="truncate font-mono text-sm">{inv.code}</code>
                <div className="flex shrink-0 items-center gap-3">
                  <span
                    className={cn(
                      "text-xs",
                      status === "active" ? "text-accent" : "text-muted-foreground"
                    )}
                  >
                    {status}
                  </span>
                  {status === "active" && (
                    <button
                      type="button"
                      onClick={() => copy(inv.code)}
                      title="Copy invite code"
                      aria-label="Copy invite code"
                      className="text-muted-foreground transition-colors hover:text-foreground"
                    >
                      {copied === inv.code ? (
                        <Check size={15} className="text-accent" />
                      ) : (
                        <Copy size={15} />
                      )}
                    </button>
                  )}
                </div>
              </div>
            );
          })
        )}
      </Card>

      {/* Accounts */}
      <h3 className="mt-8 font-display text-lg">Accounts</h3>
      <Card className="mt-3 divide-y divide-border overflow-hidden">
        {(users ?? []).map((u) => {
          const isSelf = me?.id === u.id;
          return (
            <div
              key={u.id}
              className="flex items-center justify-between gap-3 px-4 py-3"
            >
              <p className="min-w-0 truncate text-sm">
                {u.username}
                {u.is_admin && <span className="ml-1.5 text-xs text-accent">admin</span>}
                {u.disabled && (
                  <span className="ml-1.5 text-xs text-destructive">disabled</span>
                )}
                {isSelf && <span className="ml-1.5 text-xs text-muted-foreground">you</span>}
              </p>
              <div className="flex shrink-0 items-center gap-1">
                <Button
                  variant="ghost"
                  size="icon"
                  disabled={isSelf || updateUser.isPending}
                  onClick={() => updateUser.mutate({ id: u.id, is_admin: !u.is_admin })}
                  title={u.is_admin ? "Revoke admin" : "Make admin"}
                  aria-label={u.is_admin ? "Revoke admin" : "Make admin"}
                >
                  {u.is_admin ? <ShieldOff size={15} /> : <Shield size={15} />}
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  disabled={isSelf || updateUser.isPending}
                  onClick={() => updateUser.mutate({ id: u.id, disabled: !u.disabled })}
                  title={u.disabled ? "Enable account" : "Disable account"}
                  aria-label={u.disabled ? "Enable account" : "Disable account"}
                >
                  {u.disabled ? <UserCheck size={15} /> : <UserX size={15} />}
                </Button>
              </div>
            </div>
          );
        })}
      </Card>
    </section>
  );
}
