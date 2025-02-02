# FullDuplexNettyExample
An example of using netty to achieve full duplex http1.1 

There are all sorts of issues you might run into using this in production, which is why we're dropping using it. For example, if you use a load balancer, it might wait until it receives a certain amount of data to pass the call through, but full duplex requires that the server immediately issue an "OK" to the client.
