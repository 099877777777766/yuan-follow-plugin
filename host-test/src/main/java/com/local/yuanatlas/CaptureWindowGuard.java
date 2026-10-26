package com.local.yuanatlas;

/** 窗口空值表示暂不可读；等待期间不截图、不派发手势，也不推进采集步骤。 */
final class CaptureWindowGuard {
    static final long WAIT_MS=3000,RETRY_MS=200;
    interface Host {
        boolean valid(int token);
        String target();
        String focused();
        long now();
        void later(Runnable work,long delay);
        void pause(String reason);
    }
    private final Host host;
    private int waitingToken;
    private long missingSince=-1;
    CaptureWindowGuard(Host host){this.host=host;}

    void run(int token,Runnable work){
        if(!host.valid(token))return;
        if(waitingToken!=token){waitingToken=token;missingSince=-1;}
        String focused=host.focused();
        if(!host.target().isEmpty()&&host.target().equals(focused)){
            missingSince=-1;work.run();return;
        }
        if(focused!=null&&!focused.isEmpty()){
            host.pause("已切换应用，自动操作暂停");return;
        }
        long now=host.now();
        if(missingSince<0)missingSince=now;
        if(now-missingSince>=WAIT_MS){
            host.pause("持续未读到游戏窗口，已暂停；回到游戏后点播放继续");return;
        }
        // 每次重试都重新核对任务代次；暂停、结束或新任务会让旧回调失效。
        host.later(()->run(token,work),RETRY_MS);
    }
}
