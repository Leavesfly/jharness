package io.leavesfly.jharness.core.state;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 可观察的应用程序状态存储
 */
public class AppStateStore {
    
    @FunctionalInterface
    public interface Listener extends Consumer<AppState> {}
    
    private AppState state;
    private final List<Listener> listeners = new ArrayList<>();
    
    public AppStateStore(AppState initialState) {
        this.state = initialState;
    }
    
    /**
     * 返回当前状态快照
     */
    public synchronized AppState get() {
        return state;
    }
    
    /**
     * 更新状态并通知监听器
     */
    public synchronized void set(Consumer<AppState> updater) {
        updater.accept(state);
        notifyListeners();
    }
    
    /**
     * 注册监听器并返回取消订阅回调
     */
    public synchronized Runnable subscribe(Listener listener) {
        listeners.add(listener);
        
        return () -> {
            synchronized (AppStateStore.this) {
                listeners.remove(listener);
            }
        };
    }
    
    /**
     * 通知所有监听器，移除持续抛异常的监听器
     */
    private void notifyListeners() {
        // 创建副本以避免并发修改问题
        List<Listener> snapshot = new ArrayList<>(listeners);
        List<Listener> failedListeners = new ArrayList<>();
        for (Listener listener : snapshot) {
            try {
                listener.accept(state);
            } catch (Exception e) {
                failedListeners.add(listener);
            }
        }
        // 移除异常的监听器，防止性能下降
        if (!failedListeners.isEmpty()) {
            listeners.removeAll(failedListeners);
        }
    }
}