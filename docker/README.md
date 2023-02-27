# Docker Environment 🐳

`run_docker.sh` is a script to launch the image of this microservice and all the dependencies on
Docker.

## How to use 💻

You can use `local`, `dev`, `uat` or `prod` images

`sh ./run_docker.sh <local|dev|uat|prod>`

You can skip to recreate the images of Docker with `--skip-recreate`

---

ℹ️ _Note_: for **PagoPa ACR** is **required** the login `az acr login -n <acr-name>`

ℹ️ _Note_: If you run the script without the parameter, `local` is used as default.

ℹ️ _Note_: When you select `local`, a new image of this microservice is created from your branch,
but the `dev` dependencies are used.
