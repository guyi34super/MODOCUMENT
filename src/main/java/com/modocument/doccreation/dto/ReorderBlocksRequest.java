package com.modocument.doccreation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class ReorderBlocksRequest {

    /** The section's block ids in their new order — must be a permutation. */
    @NotNull
    @Size(max = 10_000)
    private List<Integer> blockIds;

    public List<Integer> getBlockIds() {
        return blockIds;
    }

    public void setBlockIds(List<Integer> blockIds) {
        this.blockIds = blockIds;
    }
}
