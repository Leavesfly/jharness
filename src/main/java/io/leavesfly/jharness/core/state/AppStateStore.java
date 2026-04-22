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
     * 通知所有监听器，移除持续抛异常的监听器。
     *
     * 调用前置条件：当前线程已持有 AppStateStore 锁（由 set() 保证）。
     * 为了避免监听器回调中反向调用 set/subscribe 造成死锁或 ConcurrentModification，
     * 这里先在持锁状态下拷贝出快照，再在快照上遍历调用；对失败监听器的移除仍然在锁内完成。
     */
    private void notifyListeners() {
        assert Thread.holdsLock(this) : "notifyListeners 必须在持有 AppStateStore 锁时调用";
        // 拷贝快照和当前状态，避免监听器反向修改导致并发问题
        List<Listener> snapshot = new ArrayList<>(listeners);
        AppState snapshotState = state;

        List<Listener> failedListeners = new ArrayList<>();
        for (Listener listener : snapshot) {
            try {
                listener.accept(snapshotState);
            } catch (Exception e) {
                failedListeners.add(listener);
            }
        }
        if (!failedListeners.isEmpty()) {
            listeners.removeAll(failedListeners);
        }
    }
}