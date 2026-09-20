package app.springbootcrm.reports;

import domain.core.ddd.annotations.TypeId;
import domain.core.persistence.AggregateRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@TypeId(Report.TYPE_ID)
@domain.core.ddd.annotations.AggregateRepository
public interface ReportRepository extends AggregateRepository<Report, UUID> {

    /** Дефолтный порядок выдачи списка — по коду; интерактивную сортировку делает список. */
    @Query("select r from Report r order by r.code asc")
    List<Report> findAllSorted();

    @Query("select r from Report r where r.code = ?1")
    Optional<Report> findByCode(String code);
}
