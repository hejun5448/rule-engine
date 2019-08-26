package org.jetlinks.rule.engine.standalone;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.commons.collections.CollectionUtils;
import org.jetlinks.rule.engine.api.Logger;
import org.jetlinks.rule.engine.api.RuleData;
import org.jetlinks.rule.engine.api.RuleDataHelper;
import org.jetlinks.rule.engine.api.events.GlobalNodeEventListener;
import org.jetlinks.rule.engine.api.events.NodeExecuteEvent;
import org.jetlinks.rule.engine.api.events.RuleEvent;
import org.jetlinks.rule.engine.api.executor.ExecutionContext;
import org.jetlinks.rule.engine.api.executor.ExecutableRuleNode;
import org.jetlinks.rule.engine.api.model.NodeType;
import org.jetlinks.rule.engine.api.executor.Input;
import org.jetlinks.rule.engine.api.executor.Output;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * @author zhouhao
 * @since 1.0.0
 */
public class DefaultRuleExecutor implements RuleExecutor {

    @Getter
    @Setter
    private Logger logger;

    @Getter
    @Setter
    private ExecutableRuleNode ruleNode;

    @Getter
    @Setter
    private NodeType nodeType;

    @Getter
    @Setter
    private String instanceId;

    @Getter
    @Setter
    private String nodeId;

    @Getter
    @Setter
    private boolean parallel;

    private volatile ExecutionContext context = new SimpleContext();

    private List<Runnable> stopListener = new ArrayList<>();

    private volatile boolean running;

    @Getter
    @Setter
    private List<GlobalNodeEventListener> listeners = new CopyOnWriteArrayList<>();

    private Set<OutRuleExecutor> outputs = new HashSet<>();


    private Map<String, List<RuleExecutor>> eventHandler = new HashMap<>();

    private Consumer<RuleData> consumer = (data) -> {
    };

    private void doNext(RuleData data) {
        (parallel ? outputs.parallelStream() : outputs.stream())
                .filter(e -> e.getCondition().test(data))
                .forEach(outRuleExecutor -> outRuleExecutor.getExecutor().execute(data));
    }

    public void start() {
        synchronized (this) {
            if (running) {
                return;
            }
            running = true;
            ruleNode.start(context);
        }
    }

    @Override
    @SneakyThrows
    public void stop() {
        synchronized (this) {
            if (context != null) {
                context.stop();
            }
            running = false;
        }
    }

    protected void fireEvent(String event, RuleData ruleData) {
        for (GlobalNodeEventListener listener : listeners) {
            listener.onEvent(NodeExecuteEvent.builder()
                    .event(event)
                    .ruleData(ruleData)
                    .instanceId(instanceId)
                    .nodeId(nodeId)
                    .build());
        }
        Optional.ofNullable(eventHandler.get(event))
                .filter(CollectionUtils::isNotEmpty)
                .ifPresent(executor ->
                        (parallel ? executor.parallelStream() : executor.stream())
                                .forEach(ruleExecutor -> ruleExecutor.execute(ruleData)));
    }

    @Override
    @SneakyThrows
    public CompletionStage<RuleData> execute(RuleData ruleData) {

        consumer.accept(ruleData);

        return CompletableFuture.completedFuture(ruleData);
    }

    @Override
    public void addNext(Predicate<RuleData> condition, RuleExecutor executor) {
        outputs.add(new OutRuleExecutor(condition, executor));
    }

    @Override
    public void addEventListener(String event, RuleExecutor executor) {
        eventHandler.computeIfAbsent(event, e -> new ArrayList<>())
                .add(executor);
    }

    @Override
    public void addEventListener(GlobalNodeEventListener listener) {
        listeners.add(listener);
    }


    private class SimpleContext implements ExecutionContext {

        @Override
        public Input getInput() {
            return new Input() {

                @Override
                public boolean accept(Consumer<RuleData> accept) {
                    consumer = accept;
                    return false;
                }

                @Override
                public void close() {

                }
            };
        }

        @Override
        public Output getOutput() {
            return DefaultRuleExecutor.this::doNext;
        }

        @Override
        public void fireEvent(String event, RuleData data) {
            data = data.copy();
            logger.debug("fire event {}.{}:{}", nodeId, event, data);
            data.setAttribute("event", event);
            DefaultRuleExecutor.this.fireEvent(event, data);
        }

        @Override
        public void onError(RuleData data, Throwable e) {
            logger().error(e.getMessage(), e);
            RuleDataHelper.putError(data, e);
            fireEvent(RuleEvent.NODE_EXECUTE_FAIL, data);
        }

        @Override
        public void stop() {
            stopListener.forEach(Runnable::run);
            //  stopListener.clear();
        }

        @Override
        public void onStop(Runnable runnable) {
            stopListener.add(runnable);
        }

        @Override
        public String getInstanceId() {
            return instanceId;
        }

        @Override
        public String getNodeId() {
            return nodeId;
        }

        @Override
        public Logger logger() {
            return logger;
        }

    }


}
