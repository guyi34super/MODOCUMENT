package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocContentBlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocContentBlockJpaRepository extends JpaRepository<DocContentBlock, Integer> {

    List<DocContentBlock> findByInstanceIdOrderBySectionIdAscOrderIndexAsc(int instanceId);

    List<DocContentBlock> findByInstanceIdAndSectionIdOrderByOrderIndex(int instanceId, int sectionId);

    Optional<DocContentBlock> findByAssetId(int assetId);

    void deleteByInstanceId(int instanceId);

    /** DSR scan (ws14): every content block of one bank. RLS backs this on PostgreSQL. */
    List<DocContentBlock> findByBankId(int bankId);

    /** ws28: the blocks of several instances, in instance, section and order. */
    List<DocContentBlock> findByInstanceIdInOrderByInstanceIdAscSectionIdAscOrderIndexAsc(
            java.util.Collection<Integer> instanceIds);
}
