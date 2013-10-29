(def vpc-id "vpc-7bc88713")
(def cf-shuppet "Shuppet-test")
(def sg-ingress [(sg-rule "tcp" 80 8080 ["10.216.221.0/24" "10.83.1.0/24"])])
(def sg-egress  [(sg-rule "-1" ["0.0.0.0/0"])])
(def elb-subnets ["subnet-24df904c" "subnet-bdc08fd5"])
(def elb-8080->8080 (elb-listener 8080 8080 "http"))
(def elb-healthcheck-ping (elb-healthcheck "HTTP:8080/1.x/ping" 2 2 6 5))
