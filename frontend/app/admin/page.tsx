"use client";

import {
  Check,
  Copy,
  Plus,
  Search,
  Shield,
  ShieldOff,
  Trash2,
  UserCheck,
  UserX,
} from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";

import { useConfirm } from "@/components/confirm-dialog";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  useClearSpentInvites,
  useCreateInvite,
  useDeleteInvite,
  useDeleteUser,
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

type UserFilter = "all" | "admins" | "disabled";

export default function AdminPage() {
  const router = useRouter();
  const { data: me } = useMe();

  // Admins only — anyone else is bounced to the library.
  useEffect(() => {
    if (me && !me.is_admin) router.replace("/");
  }, [me, router]);

  if (!me?.is_admin) return null;

  return (
    <>
      <PageHeader title="Admin" kicker="accounts & access" />
      <div className="max-w-xl space-y-12">
        <SignupSection />
        <InvitesSection />
        <AccountsSection meId={me.id} />
      </div>
    </>
  );
}

function SignupSection() {
  const { data: settings } = useSettings();
  const updateSettings = useUpdateSettings();
  const openSignup = settings?.allow_open_signup ?? false;

  return (
    <section>
      <h2 className="mb-3 font-display text-xl">Sign-up</h2>
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
              "absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-background shadow transition-transform",
              openSignup ? "translate-x-5" : "translate-x-0"
            )}
          />
        </button>
      </Card>
    </section>
  );
}

function InvitesSection() {
  const { data: invites } = useInvites();
  const createInvite = useCreateInvite();
  const deleteInvite = useDeleteInvite();
  const clearSpent = useClearSpentInvites();
  const [copied, setCopied] = useState<string | null>(null);

  const spentCount = (invites ?? []).filter((i) => inviteStatus(i) !== "active").length;

  async function copy(code: string) {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(code);
      setTimeout(() => setCopied((c) => (c === code ? null : c)), 1500);
    } catch {
      /* clipboard unavailable — the code is visible anyway */
    }
  }

  return (
    <section>
      <div className="mb-3 flex items-center justify-between gap-2">
        <h2 className="font-display text-xl">Invites</h2>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={spentCount === 0 || clearSpent.isPending}
            onClick={() => clearSpent.mutate()}
            title="Delete used and expired invites"
          >
            Clear used {spentCount > 0 && `(${spentCount})`}
          </Button>
          <Button size="sm" onClick={() => createInvite.mutate()} disabled={createInvite.isPending}>
            <Plus size={15} /> New invite
          </Button>
        </div>
      </div>
      <Card className="divide-y divide-border overflow-hidden">
        {(invites ?? []).length === 0 ? (
          <p className="px-4 py-3 text-sm text-muted-foreground">
            No invites yet. Generate one and share the code.
          </p>
        ) : (
          invites!.map((inv) => {
            const status = inviteStatus(inv);
            return (
              <div key={inv.code} className="flex items-center justify-between gap-3 px-4 py-3">
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
                  <button
                    type="button"
                    onClick={() => deleteInvite.mutate(inv.code)}
                    disabled={deleteInvite.isPending}
                    title="Delete invite"
                    aria-label="Delete invite"
                    className="text-muted-foreground transition-colors hover:text-destructive"
                  >
                    <Trash2 size={15} />
                  </button>
                </div>
              </div>
            );
          })
        )}
      </Card>
    </section>
  );
}

const FILTERS: { key: UserFilter; label: string }[] = [
  { key: "all", label: "All" },
  { key: "admins", label: "Admins" },
  { key: "disabled", label: "Disabled" },
];

function AccountsSection({ meId }: { meId: number }) {
  const { data: users } = useUsers();
  const updateUser = useUpdateUser();
  const deleteUser = useDeleteUser();
  const confirm = useConfirm();
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<UserFilter>("all");

  async function onDelete(id: number, username: string) {
    const ok = await confirm({
      title: `Delete ${username}?`,
      message:
        "This permanently deletes the account and its entire library — books, " +
        "reading progress, collections and downloads. This can't be undone.",
      confirmLabel: "Delete account",
      danger: true,
    });
    if (ok) deleteUser.mutate(id);
  }

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (users ?? []).filter((u) => {
      if (q && !u.username.toLowerCase().includes(q)) return false;
      if (filter === "admins") return u.is_admin;
      if (filter === "disabled") return u.disabled;
      return true;
    });
  }, [users, query, filter]);

  const total = users?.length ?? 0;

  return (
    <section>
      <div className="mb-3 flex items-baseline justify-between gap-2">
        <h2 className="font-display text-xl">Accounts</h2>
        <span className="text-xs text-muted-foreground">
          {query || filter !== "all" ? `${shown.length} of ${total}` : `${total} total`}
        </span>
      </div>

      <div className="mb-3 flex flex-col gap-2 sm:flex-row sm:items-center">
        <div className="relative flex-1">
          <Search
            size={15}
            className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search username…"
            className="pl-9"
          />
        </div>
        <div className="flex shrink-0 items-center gap-1 rounded-sm border border-border p-0.5">
          {FILTERS.map((f) => (
            <button
              key={f.key}
              type="button"
              onClick={() => setFilter(f.key)}
              className={cn(
                "rounded-[3px] px-2.5 py-1 text-xs transition-colors",
                filter === f.key
                  ? "bg-accent-soft text-foreground"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      <Card className="max-h-[28rem] divide-y divide-border overflow-y-auto">
        {shown.length === 0 ? (
          <p className="px-4 py-3 text-sm text-muted-foreground">No matching accounts.</p>
        ) : (
          shown.map((u) => {
            const isSelf = u.id === meId;
            return (
              <div key={u.id} className="flex items-center justify-between gap-3 px-4 py-3">
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
                  <Button
                    variant="ghost"
                    size="icon"
                    disabled={isSelf || deleteUser.isPending}
                    onClick={() => onDelete(u.id, u.username)}
                    title="Delete account"
                    aria-label="Delete account"
                    className="hover:text-destructive"
                  >
                    <Trash2 size={15} />
                  </Button>
                </div>
              </div>
            );
          })
        )}
      </Card>
    </section>
  );
}
