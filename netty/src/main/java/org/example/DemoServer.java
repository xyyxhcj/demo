package org.example;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

/**
 * 使用主从线程组的nettyServer
 * http://127.0.0.1:8088
 * curl 127.0.0.1:8088
 *
 * @author xyyxhcj@qq.com
 * @date 2020/4/17 23:51
 **/

public class DemoServer {
    public static void main(String[] args) throws InterruptedException {
        // 定义一对线程组
        // 主线程组，用于接受客户端连接
        EventLoopGroup mainGroup = new NioEventLoopGroup();
        // 从线程组，用于执行主线程组接受的任务
        EventLoopGroup subGroup = new NioEventLoopGroup();
        try {
            // 使用启动类ServerBootstrap创建netty服务器
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            serverBootstrap.group(mainGroup, subGroup)
                    .channel(NioServerSocketChannel.class)
                    // 设置初始化器:channel注册后执行的初始化方法
                    .childHandler(getChildHandler());
            // 设置channel初始化器-每个channel由多个handler共同组成管道pipeline
            // 绑定监听端口，同步监听
            ChannelFuture channelFuture = serverBootstrap.bind(8088).sync();
            // 关闭channel,使用同步监听方式
            channelFuture.channel().closeFuture().sync();
        } finally {
            // 关闭线程组
            mainGroup.shutdownGracefully();
            subGroup.shutdownGracefully();
        }
    }

    private static ChannelInitializer<SocketChannel> getChildHandler() {
        return new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel channel) throws Exception {
                // 通过channel获取对应的管道
                ChannelPipeline pipeline = channel.pipeline();
                // 通过管道pipeline添加http请求handler
                // HttpServerCodec：http编解码器
                pipeline.addLast("httpCodec", new HttpServerCodec());
                // 添加一个自定义的请求处理handler
                pipeline.addLast("helloWorldHandler", getReqHandler());
            }
        };
    }

    private static SimpleChannelInboundHandler<HttpObject> getReqHandler() {
        return new SimpleChannelInboundHandler<HttpObject>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
                if (msg instanceof HttpRequest) {
                    HttpRequest request = (HttpRequest) msg;
                    System.out.println("reqURI: " + request.uri());
                    // 从netty的上下文对象中获取channel
                    Channel channel = ctx.channel();
                    // 客户端地址
                    System.out.println("remoteAddress: " + channel.remoteAddress());
                    // 定义数据消息
                    ByteBuf byteBuf = Unpooled.copiedBuffer("hello world", CharsetUtil.UTF_8);
                    // 将数据消息响应到客户端 (http版本,响应状态responseCode,响应内容)
                    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                            HttpResponseStatus.OK, byteBuf);
                    // 设置响应数据的数据类型
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                    // 设置响应数据的可读长度
                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, byteBuf.readableBytes());
                    // 将响应输出到客户端
                    ctx.writeAndFlush(response);
                }
            }

            @Override
            public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
                System.out.println("channel注册");
                super.channelRegistered(ctx);
            }

            @Override
            public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
                System.out.println("channel注销");
                super.channelUnregistered(ctx);
            }

            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                System.out.println("channel激活处理任务..");
                super.channelActive(ctx);
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                System.out.println("channel回到待命状态");
                super.channelInactive(ctx);
            }

            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
                System.out.println("channel数据读取完毕");
                super.channelReadComplete(ctx);
            }

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                System.out.println("channel触发用户事件");
                super.userEventTriggered(ctx, evt);
            }

            @Override
            public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
                System.out.println("channel更改可写事件");
                super.channelWritabilityChanged(ctx);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                System.out.println("channel发生异常:" + cause);
                super.exceptionCaught(ctx, cause);
            }

            @Override
            public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
                System.out.println("handler已添加");
                super.handlerAdded(ctx);
            }

            @Override
            public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
                System.out.println("handler已移除");
                super.handlerRemoved(ctx);
            }
        };
    }
}
