export type EntryAmount = number | null

export function submittedAmounts(values: EntryAmount[]) {
  const amounts = [...values]
  while (amounts.length && amounts.at(-1) == null) amounts.pop()
  return amounts
}

export function advanceAmountEntry(values: EntryAmount[], index: number, maximum = 100) {
  if (Number(values[index] || 0) <= 0) return { values, focusIndex: index }
  if (index < values.length - 1) return { values, focusIndex: index + 1 }
  if (values.length >= maximum) return { values, focusIndex: index }
  return { values: [...values, null], focusIndex: values.length }
}
