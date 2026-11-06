package com.example.notificator.repository;

import com.example.notificator.model.Quote;
import org.springframework.data.jpa.repository.JpaRepository;

/** Репозиторий цитат. Цитат мало (десятки), поэтому выбор подходящей делаем в коде сервиса. */
public interface QuoteRepository extends JpaRepository<Quote, Long> {
}
