from locust import HttpUser, task

class MyUser(HttpUser):
    @task
    def test(self):
        self.client.get("/api/products/1/stock");



# source /home/jamal/Desktop/spring_boot/microservices/.venv/bin/activate
# or
# source .venv/bin/activate


# Load hits catalog directly on :8081. Each request makes catalog call out to
# inventory, which Docker's DNS balances across the two replicas -- so the load
# lands on catalog one-for-one and on the inventory pair split between them.
# locust -f locustfile.py --host=http://localhost:8081 -u 300 -r 10 -t 10s