package dev.robolink.gateway.session;

import dev.robolink.gateway.GatewayConfig;
import dev.robolink.protocol.v1.Envelope;
import dev.robolink.protocol.v1.HeartbeatReply;
import dev.robolink.protocol.v1.RegisterResult;
import dev.robolink.protocol.v1.RobotState;
import dev.robolink.protocol.v1.Telemetry;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One instance per channel. Mutable sequence and registration state stay on its event loop. */
final class DeviceConnectionHandler extends SimpleChannelInboundHandler<Envelope> {
    private final GatewayConfig config;
    private final DeviceCredentials credentials;
    private final SessionRegistry registry;
    private final IdleStateHandler idle;
    private ScheduledFuture<?> registrationDeadline;
    private DeviceSession session;
    private long lastDeviceSeq;
    private boolean terminal;

    DeviceConnectionHandler(GatewayConfig config, DeviceCredentials credentials,
                            SessionRegistry registry, IdleStateHandler idle) {
        this.config = config;
        this.credentials = credentials;
        this.registry = registry;
        this.idle = idle;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        if (ctx.channel().isActive()) { scheduleRegistrationDeadline(ctx); }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        scheduleRegistrationDeadline(ctx);
        ctx.fireChannelActive();
    }

    private void scheduleRegistrationDeadline(ChannelHandlerContext ctx) {
        if (registrationDeadline == null) {
            registrationDeadline = ctx.executor().schedule(() -> {
                if (session == null) { terminate(ctx); }
            }, config.registrationTimeoutMs(), TimeUnit.MILLISECONDS);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Envelope message) {
        if (terminal || !ctx.channel().isActive()) { return; }
        if (session == null) {
            register(ctx, message);
            return;
        }
        if (!message.getRobotId().equals(session.robotId()) || !message.getSessionId().equals(session.sessionId())) {
            terminate(ctx);
            return;
        }
        if (!message.hasHeartbeat() && !message.hasTelemetry() && !message.hasCommandAck() && !message.hasCommandResult()) {
            terminate(ctx);
            return;
        }
        if (Long.compareUnsigned(message.getSeq(), lastDeviceSeq) <= 0) {
            return; // Discard stale/duplicate traffic without refreshing liveness or telemetry.
        }
        if (message.hasTelemetry() && !validTelemetry(message.getTelemetry())) {
            terminate(ctx);
            return;
        }
        if ((message.hasCommandAck() && !DeviceCredentials.validRobotId(message.getCommandAck().getCommandId()))
                || (message.hasCommandResult() && (!DeviceCredentials.validRobotId(message.getCommandResult().getCommandId())
                || message.getCommandResult().getOutcomeValue() < 1 || message.getCommandResult().getOutcomeValue() > 3))) {
            terminate(ctx);
            return;
        }
        if (!registry.accept(session, message, System.currentTimeMillis())) {
            terminate(ctx);
            return;
        }
        lastDeviceSeq = message.getSeq();
        if (message.hasHeartbeat()) {
            ctx.writeAndFlush(reply().setHeartbeatReply(HeartbeatReply.newBuilder()
                    .setAcknowledgedSeq(message.getSeq())).build()).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        }
        // Only accepted messages reach IdleStateHandler and any future telemetry consumers.
        ctx.fireChannelRead(message);
    }

    private void register(ChannelHandlerContext ctx, Envelope message) {
        if (!message.hasRegister()) { terminate(ctx); return; }
        if (!message.getSessionId().isEmpty()
                || !credentials.authenticate(message.getRobotId(), message.getRegister().getCredential())) {
            terminal = true;
            ctx.writeAndFlush(Envelope.newBuilder().setProtocolVersion(1)
                    .setRegisterResult(RegisterResult.newBuilder().setAccepted(false)
                            .setReason("REGISTRATION_REJECTED")).build()).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        session = new DeviceSession(message.getRobotId(), ctx.channel(), System.currentTimeMillis());
        lastDeviceSeq = message.getSeq();
        registry.attach(session);
        registrationDeadline.cancel(false);
        idle.resetReadTimeout();
        ctx.writeAndFlush(reply().setRegisterResult(RegisterResult.newBuilder().setAccepted(true)
                .setAssignedSessionId(session.sessionId()).setHeartbeatIntervalMs(config.heartbeatIntervalMs())
                .setHeartbeatTimeoutMs(config.heartbeatTimeoutMs())).build())
                .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        // Do not forward Register: it contains a credential.
    }

    private Envelope.Builder reply() {
        return Envelope.newBuilder().setProtocolVersion(1).setRobotId(session.robotId())
                .setSessionId(session.sessionId()).setSeq(session.nextGatewaySeq()).setTimestampMs(System.currentTimeMillis());
    }

    private static boolean validTelemetry(Telemetry telemetry) {
        if (telemetry.getBatteryPercent() < 0 || telemetry.getBatteryPercent() > 100 || !telemetry.hasPose()) { return false; }
        var pose = telemetry.getPose();
        return Double.isFinite(pose.getXM()) && Double.isFinite(pose.getYM()) && Double.isFinite(pose.getYawRad())
                && telemetry.getState() != RobotState.ROBOT_STATE_UNSPECIFIED
                && telemetry.getState() != RobotState.UNRECOGNIZED;
    }

    private void terminate(ChannelHandlerContext ctx) { terminal = true; ctx.close(); }

    void onReadIdle(ChannelHandlerContext ctx) {
        if (session != null) { terminate(ctx); }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        terminal = true;
        if (registrationDeadline != null) { registrationDeadline.cancel(false); }
        if (session != null) { registry.remove(session); }
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (registrationDeadline != null) { registrationDeadline.cancel(false); }
        if (session != null) { registry.remove(session); }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { terminate(ctx); }
}
