# Start Docker via Colima
docker-start:
    colima status 2>/dev/null || colima start --arch aarch64 --vm-type vz --vz-rosetta --cpu 9 --memory 24 --disk 100
    # Raise inotify limits — kind nodes plus per-pod kubectl/curl
    # polling exhaust the defaults (128 instances, 8192 watches)
    # and surface as "too many open files" in init containers.
    colima ssh -- sudo sysctl -w fs.inotify.max_user_instances=8192 fs.inotify.max_user_watches=524288
    docker context use colima

# Stop Docker via Colima
docker-stop:
    colima stop

# Reclaim Colima disk space: `just docker-free-space` or `... all`
docker-free-space scope="safe":
    # FoundationDB's ratekeeper drops to zero TPS once the disk backing
    # its storage pod passes ~95% full, so every transaction times out at
    # 5s and surfaces as an unrelated-looking 500. kind nodes share this
    # filesystem with the image store and BuildKit cache.
    #
    # `all` additionally drops every image not backing a running
    # container — including locally built tags, which then need a rebuild.
    @colima ssh -- df -h /var/lib/docker | awk 'NR==2 {print "before: " $4 " free (" $5 " used)"}'
    docker container prune -f
    docker buildx prune -f
    if [ "{{ scope }}" = "all" ]; then docker image prune -a -f; else docker image prune -f; fi
    @colima ssh -- df -h /var/lib/docker | awk 'NR==2 {print "after:  " $4 " free (" $5 " used)"}'
    @echo "BuildKit's Maven cache was pruned too — run 'just docker-warm-cache' before the next build."

# Build a single service Docker image: `just docker-build api-service dev`
docker-build project tag="dev":
    docker buildx build \
      --build-arg PROJECT_NAME={{ project }} \
      -t {{ DOCKER_REGISTRY }}/{{ project }}:{{ tag }} \
      -f infra/docker/service/Dockerfile \
      --load \
      .

# Seed BuildKit's Maven cache from the host ~/.m2 (run once, and after a prune)
docker-warm-cache m2=(env_var('HOME') / ".m2"):
    # BuildKit cache mounts live in the builder, not on the host, so a
    # fresh builder re-downloads every artifact even though ~/.m2 already
    # has them — slow, and fatal on any transient DNS blip. Copies the
    # host repo in once so builds resolve offline.
    docker buildx build \
      --build-context m2={{ m2 }} \
      --progress plain \
      -f justfiles/docker/Dockerfile.warm-cache \
      --no-cache \
      -o type=cacheonly \
      .

# Build all service images in parallel via docker buildx bake
docker-build-all tag="dev":
    # Targets are declared in infra/docker/bake.hcl; the Clojure base layer is shared
    # so this is dramatically faster than building images one at a time.
    # Run `just docker-warm-cache` first on a fresh or pruned builder.
    REGISTRY={{ DOCKER_REGISTRY }} TAG={{ tag }} \
      docker buildx bake -f infra/docker/bake.hcl
