export function currentOrderDate(now = new Date()) {
  return now.toISOString()
}

export function pageAfterBulkDelete(
  currentPage: number,
  pageItemCount: number,
  deletedCount: number,
) {
  return deletedCount === pageItemCount && currentPage > 1 ? currentPage - 1 : currentPage
}
