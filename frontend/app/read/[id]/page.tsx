import { redirect } from "next/navigation";

// Reading always starts at chapter 1; deep links use /read/[id]/[position].
export default async function ReaderIndex({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  redirect(`/read/${id}/1`);
}
