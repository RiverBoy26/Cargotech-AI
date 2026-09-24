package ru.sber.cargotech.claim.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.claim.entity.Article395Rate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface Article395RateRepository extends JpaRepository<Article395Rate, UUID> {
    List<Article395Rate> findByEffectiveFromLessThanEqualOrderByEffectiveFromAsc(LocalDate date);
}
