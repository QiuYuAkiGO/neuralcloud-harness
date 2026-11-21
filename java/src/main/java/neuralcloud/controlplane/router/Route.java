package neuralcloud.controlplane.router;

// Router 首版流程契约：会议改期由 Master 继续处理，其他请求不进入派单。
public enum Route { MEETING_RESCHEDULE, UNSUPPORTED }
