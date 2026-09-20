package app.springbootcrm.catalogs.dealstage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Deal stage projection: the pipeline step plus its win probability. */
public record DealStageDto(UUID id, String code, String name,
                           BigDecimal probability, Instant createdAt) {

    public static DealStageDto of(DealStage stage) {
        return new DealStageDto(stage.getId(), stage.getCode(), stage.getName(),
                stage.getProbability(), stage.getCreatedAt());
    }
}
