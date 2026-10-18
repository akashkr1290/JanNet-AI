package com.jannetai.backend.repository;

import com.jannetai.backend.entity.HelpArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** JanNet Sahayak help articles (V38). */
@Repository
public interface HelpArticleRepository extends JpaRepository<HelpArticle, Long> {

    List<HelpArticle> findByLanguageInAndIsActiveTrueOrderBySortOrderAsc(Collection<String> languages);
}
