
all: check_code run_notebook

run_notebook: tumor_reg_data_run.html

check_code:
	# nosetests --with-doctest
	# or tox?
	python -m doctest tumor_reg_ont.py
	python -m doctest tumor_reg_data.py
	python -m doctest tumor_reg_tasks.py
	python -m doctest sql_script.py

check_code_todo:
	pyflakes .
	# mypy --strict --show-traceback .
	mypy --strict .

tumor_reg_data_run.html: tumor_reg_data_run.ipynb
	jupyter nbconvert --to html $< $@

tumor_reg_data_run.ipynb: tumor_reg_data.ipynb
	PYSPARK_DRIVER_PYTHON=python spark-submit run_nb.py $< $@

tumor_reg_data.ipynb tumor_reg_data.py:
	jupytext --sync tumor_reg_data.ipynb