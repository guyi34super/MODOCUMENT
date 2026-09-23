package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocImageAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocImageAssetJpaRepository extends JpaRepository<DocImageAsset, Integer> {

    List<DocImageAsset> findByInstanceIdOrderByDocImageAssetId(int instanceId);

    void deleteByInstanceId(int instanceId);
}
