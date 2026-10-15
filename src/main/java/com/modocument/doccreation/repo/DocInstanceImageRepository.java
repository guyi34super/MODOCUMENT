package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocInstanceImage;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** V302: the photos of documents; every call names the caller's bank. */
public interface DocInstanceImageRepository {

    Optional<DocInstanceImage> getById(int bankId, int id);

    /** One document's photos, tray order. */
    List<DocInstanceImage> listByInstance(int bankId, int instanceId);

    /** The photos of several documents, keyed by instance id (release queue). */
    Map<Integer, List<DocInstanceImage>> listByInstances(int bankId, Collection<Integer> instanceIds);

    DocInstanceImage save(DocInstanceImage image);

    List<DocInstanceImage> saveAll(List<DocInstanceImage> images);

    void delete(DocInstanceImage image);

    void deleteByInstance(int bankId, int instanceId);
}
