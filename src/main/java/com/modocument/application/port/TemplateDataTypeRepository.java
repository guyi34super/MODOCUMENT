package com.modocument.application.port;

import com.modocument.domain.entity.TemplateDataType;

import java.util.List;
import java.util.Optional;

public interface TemplateDataTypeRepository {

    Optional<TemplateDataType> getById(int id);

    List<TemplateDataType> list();

    void add(TemplateDataType entity);

    TemplateDataType update(TemplateDataType entity);

    void delete(int id);
}
