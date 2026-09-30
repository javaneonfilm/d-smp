#!/bin/bash
# Dev-server helper. srv.sh start|stop|restart|cmd "<console command>"|log [n]|build
D="$(cd "$(dirname "$0")/../dev-server" && pwd)"
P="$(cd "$(dirname "$0")/../plugin" && pwd)"
case "$1" in
  build)
    cd "$P" && JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" "../../maven-install/apache-maven-3.9.6/bin/mvn" -q -o package 2>&1 | grep -E "ERROR|FAIL|Tests run" | head -20
    cp "$P/target/CartRoyale.jar" "$D/plugins/" && echo "jar copied" ;;
  start)
    pkill -f "tail -f cmds.txt" 2>/dev/null; cd "$D" && rm -f cmds.txt && touch cmds.txt && : > server.log
    (tail -f cmds.txt | java -Xmx3G -jar paper.jar nogui > server.log 2>&1 &)
    for i in $(seq 1 120); do grep -q "Done (" server.log && break; sleep 1; done
    grep -E "Done \(|CartRoyale\]|ERROR|Exception" server.log | grep -v "No key layers" | tail -8 ;;
  stop)
    cd "$D" && echo "stop" >> cmds.txt
    for i in $(seq 1 60); do tasklist 2>/dev/null | grep -qi "java.exe" || break; sleep 1; done
    pkill -f "tail -f cmds.txt" 2>/dev/null; sleep 2; echo stopped ;;
  restart) "$0" stop; "$0" start ;;
  cmd)
    cd "$D" && n=$(wc -l < server.log); echo "$2" >> cmds.txt; sleep "${3:-1.5}"; tail -n +$((n+1)) server.log | grep -v "No key layers"; true ;;
  log) tail -n "${2:-40}" "$D/server.log" | grep -v "No key layers" ;;
esac
