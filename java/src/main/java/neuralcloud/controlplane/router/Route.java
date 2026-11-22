package neuralcloud.controlplane.router;

// 路由表示当前请求所需的处理方式。工具能力与授权由执行层校验。
public enum Route { CONVERSATION, ACTION_REQUEST, UNSUPPORTED }
