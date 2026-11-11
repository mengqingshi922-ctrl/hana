package com.hana.agent.edge;

import android.os.Bundle;

/**
 * 端侧推理独立子进程 (:edge_inference) IPC 跨进程通信契约
 * 隔离大模型推理 Native 堆栈与计算负载，防止主无障碍进程遭遇 LMK 强杀
 */
interface IEdgeInference {
    /** 探针检测当前子进程推理引擎是否就绪 */
    boolean isAvailable();

    /** 意图理解与分类 */
    String classifyIntent(String task);

    /** 跨进程视觉元素坐标定位 */
    String locateVisualElement(in byte[] jpegShot, String query);

    /** 通用端侧推理通道 */
    Bundle runInference(in Bundle input);
}
