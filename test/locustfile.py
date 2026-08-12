from locust import HttpUser, task

class MyUser(HttpUser):
    @task
    def test(self):
        self.client.get("/api/products/1/stock");



# source /home/jamal/Desktop/spring_boot/microservices/.venv/bin/activate
# or
# source .venv/bin/activate


# Load goes through the gateway on :80 -- services publish no host ports, so
# :8081 no longer exists. /api/products routes to catalog_backend, and catalog's
# own call out to inventory is balanced separately by Docker DNS across the two
# replicas. One request, both balancing layers.
# locust -f locustfile.py --host=http://localhost -u 300 -r 10 -t 10s