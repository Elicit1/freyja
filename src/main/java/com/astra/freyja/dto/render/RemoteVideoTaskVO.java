package com.astra.freyja.dto.render;

/** Exact upstream task evidence; UNKNOWN never authorizes releasing the render slot. */
public record RemoteVideoTaskVO(String status, String videoUrl, String promptId, String nodeId) {}
