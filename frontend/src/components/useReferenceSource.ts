import { useMemo } from "react";
import { useMetadata } from "../metadata/MetadataProvider";
import type { ReferenceSource } from "./filterTypes";
import { buildReferenceSource } from "./referenceSource";

/**
 * Будує {@link ReferenceSource} для заданого {@code refTypeId} (inline-picker:
 * {@code RefField} / {@code RefAutocompleteInput} / {@code ReferencePicker}).
 *
 * <p>Уся логіка диспатчу (користувацький picker-конфіг vs universal fallback)
 * винесена у спільний {@link buildReferenceSource} — щоб filter-picker
 * ({@code ListView}) і inline-picker гарантовано будували <b>однакове</b>
 * джерело для одного типу (раніше це були дві копії, що розходилися).
 * Тут лишається тільки React-обгортка з мемоїзацією.
 */
export function useReferenceSource(
  refTypeId: number | null | undefined,
): ReferenceSource<any> | null {
  const { byTypeId } = useMetadata();
  return useMemo(
    () => buildReferenceSource(refTypeId, byTypeId),
    [refTypeId, byTypeId],
  );
}
