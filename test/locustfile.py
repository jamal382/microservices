from locust import HttpUser, task

class MyUser(HttpUser):
    @task
    def test(self):
        self.client.get("/api/products/1/stock");



# source /home/jamal/Desktop/spring_boot/microservices/.venv/bin/activate
# or
# source .venv/bin/activate


# locust -f locustfile.py --host=http://localhost:8081 -u 300 -r 10 -t 10s