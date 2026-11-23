package neuralcloud.controlplane.router;

/**
 * Router 对话分流使用的通用业务路由枚举。
 * 路由只表达请求的性质分类，具体的工具能力是否已挂载、权限是否满足仍交由执行层校验：
 * - {@link #CONVERSATION}：纯交流或问答，由 Chat Worker 直接生成角色回复；
 * - {@link #ACTION_REQUEST}：需要执行外部或现场动作（如读取服务器状态），交由任务执行层处理；
 * - {@link #UNSUPPORTED}：当前控制面不具备对应能力或超出范围的请求。
 */
public enum Route { CONVERSATION, ACTION_REQUEST, UNSUPPORTED }
