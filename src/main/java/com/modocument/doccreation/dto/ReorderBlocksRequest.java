package com.modocument.doccreation.dto;

import java.util.List;

public class ReorderBlocksRequest {

    /** The section's block ids in their new order — must be a permutation. */
    private List<Integer> blockIds;

    public List<Integer> getBlockIds() {
        return blockIds;
    }

    public void setBlockIds(List<Integer> blockIds) {
        this.blockIds = blockIds;
    }
}
